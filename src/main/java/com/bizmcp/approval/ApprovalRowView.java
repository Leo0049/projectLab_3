package com.bizmcp.approval;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * One row of the approval console.
 *
 * <p>Spec section 14 asks the console to "show the before/after preview", and
 * the stored preview is JSON. A JSON blob technically <em>contains</em> before
 * and after without showing them: an approver reading
 * {@code {"after":100,"delta":-20,"before":120,"productName":"珍珠奶茶"}} under
 * time pressure is parsing, not judging, and an approval nobody really read is
 * the rubber stamp this whole mechanism exists to prevent.
 *
 * <p>So this turns the preview into one line a person can act on, and keeps the
 * raw JSON next to it in a disclosure. Summarising is not the same as hiding:
 * the approver is deciding on unmasked production values (spec section 7.4) and
 * must always be able to see exactly what was requested.
 */
public record ApprovalRowView(
        String id,
        String toolName,
        ApprovalStatus status,

        /** What is being changed — "珍珠奶茶" — or null if the preview has no before/after pair. */
        String subject,
        /** "120 → 100". Kept separate from {@link #subject} so it never wraps mid-arrow. */
        String change,
        /** "-20" / "+20", or null. */
        String deltaLabel,
        boolean decrease,
        /** Warnings and context that would otherwise be buried in the JSON. */
        List<Note> notes,
        /** Fallback rendering for previews this view does not recognise. */
        List<Field> previewFields,
        String rawPreview,

        List<Field> arguments,
        String rawArguments,

        /** "9 分鐘後到期（21:59）", or "21:52 已執行" once decided. */
        String timing,
        boolean urgent,

        String failureReason,
        String rejectReason,
        int retryCount) {

    /** A short line under the headline. {@code level} drives the colour only. */
    public record Note(String level, String text) {
        static Note warn(String text) {
            return new Note("warn", text);
        }

        static Note info(String text) {
            return new Note("info", text);
        }
    }

    /** A labelled value, used for arguments and for unrecognised previews. */
    public record Field(String label, String value) {
    }

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static final Duration URGENT_WITHIN = Duration.ofMinutes(5);

    /**
     * Argument names the console knows how to label; anything else prints raw.
     * The order is the reading order, because jsonb does not preserve the
     * order the arguments arrived in — "品項編號" belongs above "增減量".
     */
    private static final List<String> ARGUMENT_ORDER =
            List.of("productId", "delta", "reason", "storeId", "orderId");

    private static final Map<String, String> ARGUMENT_LABELS = Map.of(
            "productId", "品項編號",
            "delta", "增減量",
            "reason", "原因",
            "storeId", "門市編號",
            "orderId", "訂單編號");

    /** Preview keys consumed by {@link #headline}; not repeated as fields. */
    private static final List<String> HEADLINE_KEYS =
            List.of("productName", "before", "after", "delta", "affectedOrders");

    public static ApprovalRowView of(ApprovalRequest request,
                                     Map<String, Object> preview,
                                     Map<String, Object> arguments,
                                     OffsetDateTime now,
                                     ZoneId zone) {
        List<Note> notes = new ArrayList<>();
        String subject = null;
        String change = null;
        String deltaLabel = null;
        boolean decrease = false;
        List<Field> previewFields = new ArrayList<>();

        Object error = preview.get("error");
        if (error != null) {
            notes.add(Note.warn(String.valueOf(error)));
        } else if (preview.get("before") instanceof Number before
                   && preview.get("after") instanceof Number after) {
            subject = preview.get("productName") == null
                    ? "數量" : String.valueOf(preview.get("productName"));
            change = "%s → %s".formatted(count(before), count(after));

            if (preview.get("delta") instanceof Number delta) {
                decrease = delta.longValue() < 0;
                deltaLabel = (delta.longValue() > 0 ? "+" : "") + count(delta);
            }
            if (after.longValue() < 0) {
                // Without this the row reads "after: -999984", which looks like
                // the system miscalculated. It did not: the write guards against
                // negative stock, so this request cannot succeed as written.
                notes.add(Note.warn("調整後庫存為負數，核准後執行會被拒絕（庫存不得小於 0）"));
            }
            if (preview.get("affectedOrders") instanceof Number affected
                && affected.intValue() > 0) {
                notes.add(Note.info("牽動 %d 筆未結訂單".formatted(affected.intValue())));
            }
        }

        // Anything the headline did not consume still has to be visible.
        preview.forEach((key, value) -> {
            boolean consumed = "error".equals(key)
                               || (headlineBuilt(preview) && HEADLINE_KEYS.contains(key));
            if (!consumed) {
                previewFields.add(new Field(key, String.valueOf(value)));
            }
        });

        List<Field> argumentFields = arguments.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> {
                    int index = ARGUMENT_ORDER.indexOf(e.getKey());
                    return index < 0 ? ARGUMENT_ORDER.size() : index;
                }))
                .map(e -> new Field(ARGUMENT_LABELS.getOrDefault(e.getKey(), e.getKey()),
                        String.valueOf(e.getValue())))
                .toList();

        return new ApprovalRowView(
                request.getId(),
                request.getToolName(),
                request.getStatus(),
                subject,
                change,
                deltaLabel,
                decrease,
                List.copyOf(notes),
                List.copyOf(previewFields),
                request.getPreview(),
                argumentFields,
                request.getArguments(),
                timing(request, now, zone),
                isUrgent(request, now),
                request.getFailureReason(),
                request.getRejectReason(),
                request.getRetryCount());
    }

    private static boolean headlineBuilt(Map<String, Object> preview) {
        return preview.get("before") instanceof Number && preview.get("after") instanceof Number;
    }

    /**
     * Replaces a raw ISO instant such as {@code 2026-08-14T13:59:27.980013Z}.
     * An approver deciding whether a request is about to lapse should not have
     * to subtract microseconds and convert time zones in their head.
     */
    private static String timing(ApprovalRequest request, OffsetDateTime now, ZoneId zone) {
        OffsetDateTime expiresAt = request.getExpiresAt();
        return switch (request.getStatus()) {
            case PENDING -> {
                Duration left = Duration.between(now, expiresAt);
                yield left.isNegative() || left.isZero()
                        ? "已逾期，等待系統標記"
                        : "%s後到期（%s）".formatted(humanise(left), local(expiresAt, zone));
            }
            case APPROVED -> "執行中";
            case EXECUTED -> "%s 已執行".formatted(local(request.getExecutedAt(), zone));
            case EXECUTION_FAILED -> "%s 執行失敗".formatted(local(request.getExecutedAt() != null
                    ? request.getExecutedAt() : request.getDecidedAt(), zone));
            case REJECTED -> "%s 已駁回".formatted(local(request.getDecidedAt(), zone));
            case EXPIRED -> "%s 逾期作廢".formatted(local(expiresAt, zone));
            case ABANDONED -> "%s 已放棄".formatted(local(request.getDecidedAt(), zone));
        };
    }

    private static boolean isUrgent(ApprovalRequest request, OffsetDateTime now) {
        if (request.getStatus() != ApprovalStatus.PENDING) {
            return false;
        }
        return Duration.between(now, request.getExpiresAt()).compareTo(URGENT_WITHIN) < 0;
    }

    private static String humanise(Duration left) {
        if (left.toMinutes() < 1) {
            return left.toSeconds() + " 秒";
        }
        if (left.toHours() < 1) {
            return left.toMinutes() + " 分鐘";
        }
        if (left.toDays() < 1) {
            return left.toHours() + " 小時";
        }
        return left.toDays() + " 天";
    }

    private static String local(OffsetDateTime moment, ZoneId zone) {
        return moment == null ? "—" : CLOCK.format(moment.atZoneSameInstant(zone));
    }

    /** Grouped, so a five-digit stock figure is not mistaken for a four-digit one. */
    private static String count(Number value) {
        return String.format("%,d", value.longValue());
    }
}
