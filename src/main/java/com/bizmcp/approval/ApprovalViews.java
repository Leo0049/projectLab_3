package com.bizmcp.approval;

import java.util.Map;

/** Payloads returned to the model by the approval-aware tools. */
public final class ApprovalViews {

    private ApprovalViews() {
    }

    /**
     * Returned by a T3 tool instead of performing the write (spec section 8.1).
     * The message tells the model exactly how to continue, which is what stops
     * the conversation dead-ending the way it did in v1.0.
     */
    public record PendingApproval(
            String status,
            String approvalId,
            String message,
            Map<String, Object> preview
    ) {
        public static PendingApproval of(String approvalId, Map<String, Object> preview) {
            return new PendingApproval(
                    "PENDING_APPROVAL",
                    approvalId,
                    "此操作需人工核准，尚未生效。請告知使用者前往 /approvals 確認；"
                    + "核准後請用 check_approval_status（approvalId=%s）查詢實際結果，"
                    + "在查到 EXECUTED 之前不要向使用者宣稱已完成。".formatted(approvalId),
                    preview);
        }
    }

    /**
     * Returned by {@code check_approval_status}. Carries the failure reason so
     * the model can say "approved but it did not go through" rather than
     * quietly assuming success (spec section 8.2).
     */
    public record ApprovalStatusView(
            String approvalId,
            String toolName,
            String status,
            String interpretation,
            Map<String, Object> preview,
            String failureReason,
            String rejectReason,
            int retryCount,
            String decidedAt,
            String executedAt
    ) {
    }
}
