package com.bizmcp.suitea;

import com.bizmcp.approval.ApprovalRequest;
import com.bizmcp.approval.ApprovalRowView;
import com.bizmcp.approval.ApprovalStatus;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The approval console's rendering (spec section 14, "顯示 before/after preview").
 *
 * <p>These read like cosmetics and are not. An approver who cannot see, in one
 * glance, what a request does is a rubber stamp, and a rubber stamp is exactly
 * the failure mode the approval gate exists to prevent.
 */
class ApprovalConsoleViewTest {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 8, 14, 13, 50, 0, 0, ZoneOffset.UTC);

    @Test
    void aStockChangeReadsAsBeforeAndAfter() {
        ApprovalRowView row = render(pending(), preview("珍珠奶茶", 120, 100, -20, 0));

        assertThat(row.subject()).isEqualTo("珍珠奶茶");
        assertThat(row.change()).isEqualTo("120 → 100");
        assertThat(row.deltaLabel()).isEqualTo("-20");
        assertThat(row.decrease()).isTrue();
    }

    @Test
    void anIncreaseIsSignedAndNotMarkedAsADecrease() {
        ApprovalRowView row = render(pending(), preview("珍珠奶茶", 100, 120, 20, 0));

        assertThat(row.deltaLabel()).isEqualTo("+20");
        assertThat(row.decrease()).isFalse();
    }

    @Test
    void aChangeThatWouldGoNegativeSaysSoInsteadOfShowingAStrangeNumber() {
        // The demo's failure case. Rendered as raw JSON this shows
        // "after": -999984, which reads as a miscalculation rather than as a
        // request that cannot succeed.
        ApprovalRowView row = render(pending(), preview("珍珠奶茶", 16, -999984, -1000000, 0));

        assertThat(row.subject()).isEqualTo("珍珠奶茶");
        assertThat(row.change()).isEqualTo("16 → -999,984");
        assertThat(row.notes())
                .extracting(ApprovalRowView.Note::text)
                .anyMatch(text -> text.contains("負數") && text.contains("拒絕"));
        assertThat(row.notes()).extracting(ApprovalRowView.Note::level).contains("warn");
    }

    @Test
    void openOrdersAreCalledOutBecauseTheyAreTheReasonToHesitate() {
        ApprovalRowView row = render(pending(), preview("珍珠奶茶", 120, 100, -20, 3));

        assertThat(row.notes()).extracting(ApprovalRowView.Note::text)
                .anyMatch(text -> text.contains("3") && text.contains("未結訂單"));
    }

    @Test
    void noOpenOrdersProducesNoNoise() {
        ApprovalRowView row = render(pending(), preview("珍珠奶茶", 120, 100, -20, 0));

        assertThat(row.notes()).isEmpty();
    }

    @Test
    void aPendingRequestShowsHowLongIsLeftInLocalTime() {
        ApprovalRequest request = pending();
        request.setExpiresAt(NOW.plusMinutes(9));

        ApprovalRowView row = render(request, preview("珍珠奶茶", 120, 100, -20, 0));

        // 13:59 UTC is 21:59 in Taipei; the raw column used to print
        // "2026-08-14T13:59:27.980013Z" and leave the conversion to the reader.
        assertThat(row.timing()).isEqualTo("9 分鐘後到期（21:59）");
        assertThat(row.urgent()).isFalse();
    }

    @Test
    void aRequestAboutToLapseIsMarkedUrgent() {
        ApprovalRequest request = pending();
        request.setExpiresAt(NOW.plusMinutes(2));

        assertThat(render(request, Map.of()).urgent()).isTrue();
    }

    @Test
    void anExpiredPendingRequestSaysSoRatherThanCountingDownwardsForever() {
        ApprovalRequest request = pending();
        request.setExpiresAt(NOW.minusMinutes(1));

        ApprovalRowView row = render(request, Map.of());

        assertThat(row.timing()).contains("已逾期");
        assertThat(row.urgent()).isTrue();
    }

    @Test
    void aDecidedRequestShowsWhenItHappenedNotWhenItWouldHaveLapsed() {
        ApprovalRequest request = pending();
        request.setStatus(ApprovalStatus.EXECUTED);
        request.setDecidedAt(NOW.minusMinutes(3));
        request.setExecutedAt(NOW.minusMinutes(3));

        ApprovalRowView row = render(request, preview("珍珠奶茶", 120, 100, -20, 0));

        assertThat(row.timing()).isEqualTo("21:47 已執行");
        assertThat(row.urgent()).isFalse();
    }

    @Test
    void argumentsAreLabelledAndTheRawFormIsKept() {
        ApprovalRequest request = pending();
        request.setArguments("{\"productId\":1001,\"delta\":-20}");

        ApprovalRowView row = ApprovalRowView.of(request,
                Map.of(),
                new LinkedHashMap<>(Map.of("productId", 1001, "delta", -20)),
                NOW, TAIPEI);

        assertThat(row.arguments()).extracting(ApprovalRowView.Field::label)
                .contains("品項編號", "增減量");
        assertThat(row.rawArguments()).isEqualTo("{\"productId\":1001,\"delta\":-20}");
    }

    @Test
    void argumentsReadInAFixedOrderRegardlessOfHowJsonbStoredThem() {
        // jsonb does not preserve insertion order, so the column would otherwise
        // shuffle between rows showing the same tool.
        Map<String, Object> asStored = new LinkedHashMap<>();
        asStored.put("delta", -20);
        asStored.put("reason", "盤點差異");
        asStored.put("productId", 1001);

        ApprovalRowView row = ApprovalRowView.of(pending(), Map.of(), asStored, NOW, TAIPEI);

        assertThat(row.arguments()).extracting(ApprovalRowView.Field::label)
                .containsExactly("品項編號", "增減量", "原因");
    }

    @Test
    void aPreviewThisViewDoesNotUnderstandIsStillShownInFull() {
        // A future T3 executor must not silently render as a blank cell.
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("somethingNew", "值");

        ApprovalRowView row = render(pending(), preview);

        assertThat(row.change()).isNull();
        assertThat(row.previewFields()).extracting(ApprovalRowView.Field::label)
                .containsExactly("somethingNew");
    }

    @Test
    void keysBesideTheHeadlineAreNotDroppedOnTheFloor() {
        Map<String, Object> preview = preview("珍珠奶茶", 120, 100, -20, 0);
        preview.put("warehouse", "台北倉");

        assertThat(render(pending(), preview).previewFields())
                .extracting(ApprovalRowView.Field::label)
                .containsExactly("warehouse");
    }

    @Test
    void aPreviewThatCouldNotBeBuiltShowsItsError() {
        ApprovalRowView row = render(pending(), Map.of("error", "找不到品項 9999"));

        assertThat(row.change()).isNull();
        assertThat(row.notes()).extracting(ApprovalRowView.Note::text)
                .containsExactly("找不到品項 9999");
        assertThat(row.previewFields()).isEmpty();
    }

    @Test
    void theRawPreviewIsAlwaysRetained() {
        // Summarising is not hiding: the approver decides on unmasked values
        // (spec section 7.4) and must be able to see exactly what was requested.
        ApprovalRequest request = pending();
        request.setPreview("{\"before\":120,\"after\":100}");

        assertThat(render(request, preview("珍珠奶茶", 120, 100, -20, 0)).rawPreview())
                .isEqualTo("{\"before\":120,\"after\":100}");
    }

    // --- fixtures ---------------------------------------------------------

    private ApprovalRowView render(ApprovalRequest request, Map<String, Object> preview) {
        return ApprovalRowView.of(request, preview, Map.of(), NOW, TAIPEI);
    }

    private static ApprovalRequest pending() {
        ApprovalRequest request = new ApprovalRequest();
        request.setId("apr_demo");
        request.setToolName("adjust_inventory");
        request.setStatus(ApprovalStatus.PENDING);
        request.setArguments("{}");
        request.setPreview("{}");
        request.setExpiresAt(NOW.plusMinutes(9));
        request.setRetryCount((short) 0);
        return request;
    }

    private static Map<String, Object> preview(String name, int before, int after,
                                               int delta, int affectedOrders) {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("productName", name);
        preview.put("before", before);
        preview.put("after", after);
        preview.put("delta", delta);
        preview.put("affectedOrders", affectedOrders);
        return preview;
    }
}
