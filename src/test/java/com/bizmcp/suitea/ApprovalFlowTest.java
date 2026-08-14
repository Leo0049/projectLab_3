package com.bizmcp.suitea;

import com.bizmcp.approval.ApprovalRequest;
import com.bizmcp.approval.ApprovalRequestRepository;
import com.bizmcp.approval.ApprovalService;
import com.bizmcp.approval.ApprovalStatus;
import com.bizmcp.governance.Role;
import com.bizmcp.support.GovernanceTestBase;
import com.bizmcp.support.ToolArguments;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Write governance end to end (spec sections 8.1, 8.2): nothing is written
 * until a human approves, approving twice writes once, and a failed execution
 * is recorded as such instead of being silently retried.
 */
class ApprovalFlowTest extends GovernanceTestBase {

    private static final long APPROVER_ID = 45L;

    @Autowired ApprovalService approvalService;
    @Autowired ApprovalRequestRepository approvalRepository;
    @Autowired JdbcTemplate jdbc;

    @Test
    void aWriteToolDoesNotWriteAnythingByItself() {
        actAs(Role.STORE_MANAGER, TENANT_A);
        int before = quantityOf(ToolArguments.PRODUCT_PEARL);

        String payload = tools.callForWirePayload("adjust_inventory", Map.of(
                "productId", ToolArguments.PRODUCT_PEARL,
                "delta", -50,
                "reason", "盤點差異"));

        assertThat(payload).contains("PENDING_APPROVAL");
        assertThat(payload).contains("apr_");
        // The impact preview is what makes the approval a decision, not a rubber stamp.
        assertThat(payload).contains("\"before\"").contains("\"after\"");
        assertThat(quantityOf(ToolArguments.PRODUCT_PEARL))
                .as("stock must not move before approval")
                .isEqualTo(before);
    }

    @Test
    void approvingTwiceAppliesTheWriteOnce() {
        actAs(Role.STORE_MANAGER, TENANT_A);
        int before = quantityOf(ToolArguments.PRODUCT_PEARL);

        tools.call("adjust_inventory", Map.of(
                "productId", ToolArguments.PRODUCT_PEARL, "delta", -50, "reason", "盤點差異"));
        String approvalId = latestPendingId();

        ApprovalRequest first = approvalService.approve(approvalId, APPROVER_ID, TENANT_A);
        assertThat(first.getStatus()).isEqualTo(ApprovalStatus.EXECUTED);
        assertThat(quantityOf(ToolArguments.PRODUCT_PEARL)).isEqualTo(before - 50);

        // The approval id doubles as the idempotency key.
        ApprovalRequest second = approvalService.approve(approvalId, APPROVER_ID, TENANT_A);
        assertThat(second.getStatus()).isEqualTo(ApprovalStatus.EXECUTED);
        assertThat(quantityOf(ToolArguments.PRODUCT_PEARL))
                .as("re-approving must not apply the delta twice")
                .isEqualTo(before - 50);
    }

    @Test
    void aFailedExecutionIsRecordedRatherThanRetriedSilently() {
        actAs(Role.STORE_MANAGER, TENANT_A);
        int before = quantityOf(ToolArguments.PRODUCT_PEARL);

        // Would drive stock negative, so the guarded UPDATE matches no rows.
        tools.call("adjust_inventory", Map.of(
                "productId", ToolArguments.PRODUCT_PEARL, "delta", -999_999, "reason", "錯誤的盤點"));
        String approvalId = latestPendingId();

        ApprovalRequest result = approvalService.approve(approvalId, APPROVER_ID, TENANT_A);

        assertThat(result.getStatus()).isEqualTo(ApprovalStatus.EXECUTION_FAILED);
        assertThat(result.getFailureReason()).isNotBlank();
        assertThat(quantityOf(ToolArguments.PRODUCT_PEARL)).isEqualTo(before);
    }

    @Test
    void checkApprovalStatusTellsTheModelTheTruthAboutAFailure() {
        actAs(Role.STORE_MANAGER, TENANT_A);
        tools.call("adjust_inventory", Map.of(
                "productId", ToolArguments.PRODUCT_PEARL, "delta", -999_999, "reason", "錯誤的盤點"));
        String approvalId = latestPendingId();
        approvalService.approve(approvalId, APPROVER_ID, TENANT_A);

        String payload = tools.callForWirePayload("check_approval_status",
                Map.of("approvalId", approvalId));

        assertThat(payload).contains("EXECUTION_FAILED");
        // The model must be able to say "approved, but it did not happen".
        assertThat(payload).contains("執行失敗");
        assertThat(payload).doesNotContain("EXECUTED\"");
    }

    @Test
    void rejectionLeavesTheDataUntouched() {
        actAs(Role.STORE_MANAGER, TENANT_A);
        int before = quantityOf(ToolArguments.PRODUCT_PEARL);

        tools.call("adjust_inventory", Map.of(
                "productId", ToolArguments.PRODUCT_PEARL, "delta", -10, "reason", "測試駁回"));
        String approvalId = latestPendingId();

        approvalService.reject(approvalId, APPROVER_ID, TENANT_A, "數量不合理");

        assertThat(approvalRepository.findById(approvalId).orElseThrow().getStatus())
                .isEqualTo(ApprovalStatus.REJECTED);
        assertThat(quantityOf(ToolArguments.PRODUCT_PEARL)).isEqualTo(before);

        actAs(Role.STORE_MANAGER, TENANT_A);
        assertThat(tools.callForWirePayload("check_approval_status", Map.of("approvalId", approvalId)))
                .contains("REJECTED").contains("數量不合理");
    }

    @Test
    void anExpiredRequestCannotBeApprovedLater() {
        actAs(Role.STORE_MANAGER, TENANT_A);
        tools.call("adjust_inventory", Map.of(
                "productId", ToolArguments.PRODUCT_PEARL, "delta", -5, "reason", "測試逾時"));
        String approvalId = latestPendingId();

        // Backdate the TTL rather than waiting 30 minutes.
        ApprovalRequest request = approvalRepository.findById(approvalId).orElseThrow();
        request.setExpiresAt(OffsetDateTime.now().minusMinutes(1));
        approvalRepository.saveAndFlush(request);

        assertThat(approvalService.expireOverdue()).isGreaterThanOrEqualTo(1);
        assertThat(approvalRepository.findById(approvalId).orElseThrow().getStatus())
                .isEqualTo(ApprovalStatus.EXPIRED);

        assertThatThrownBy(() -> approvalService.approve(approvalId, APPROVER_ID, TENANT_A))
                .isInstanceOf(ApprovalService.IllegalApprovalTransitionException.class);
    }

    @Test
    void anApprovalFromAnotherTenantIsInvisible() {
        actAs(Role.STORE_MANAGER, TENANT_A);
        tools.call("adjust_inventory", Map.of(
                "productId", ToolArguments.PRODUCT_PEARL, "delta", -1, "reason", "跨租戶測試"));
        String approvalId = latestPendingId();

        assertThatThrownBy(() -> approvalService.approve(approvalId, APPROVER_ID, TENANT_B))
                .isInstanceOf(ApprovalService.ApprovalNotFoundException.class);

        actAs(Role.STORE_MANAGER, TENANT_B);
        assertThat(tools.callForWirePayload("check_approval_status", Map.of("approvalId", approvalId)))
                .contains("找不到審批單");
    }

    private int quantityOf(long productId) {
        Integer quantity = jdbc.queryForObject(
                "SELECT quantity FROM inventory WHERE product_id = ? AND merchant_id = ?",
                Integer.class, productId, TENANT_A);
        return quantity == null ? 0 : quantity;
    }

    private String latestPendingId() {
        return approvalRepository.findByStatusOrderByCreatedAtDesc(ApprovalStatus.PENDING).stream()
                .filter(request -> request.getTenantId() == TENANT_A)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no pending approval was created"))
                .getId();
    }
}
