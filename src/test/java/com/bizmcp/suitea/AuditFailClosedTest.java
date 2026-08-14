package com.bizmcp.suitea;

import com.bizmcp.audit.AuditLog;
import com.bizmcp.audit.AuditLogRepository;
import com.bizmcp.governance.GovernanceAspect;
import com.bizmcp.governance.Role;
import com.bizmcp.support.GovernanceTestBase;
import com.bizmcp.support.ToolArguments;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit fail-closed behaviour and the transaction boundary that makes it real
 * (spec section 9.1).
 */
class AuditFailClosedTest extends GovernanceTestBase {

    @Autowired AuditLogRepository auditLogRepository;

    @Test
    void aFailedToolCallStillLeavesAnAuditRecord() {
        actAs(Role.ADMIN, TENANT_A);

        // endDate before startDate makes the service throw mid-call.
        tools.call("query_sales_summary", Map.of(
                "startDate", "2026-01-31",
                "endDate", "2026-01-01",
                "groupBy", "STORE"));

        AuditLog latest = auditLogRepository.findByToolNameOrderByIdDesc("query_sales_summary").get(0);

        // The pre-write committed in its own transaction, so the record survives
        // the failure. This is the whole point of REQUIRES_NEW: if the audit row
        // shared the business transaction, a failed call would leave no trace.
        assertThat(latest.getDecision()).isEqualTo("ALLOW");
        assertThat(latest.getOutcome()).isEqualTo("FAILED");
        assertThat(latest.getLatencyMs()).isNotNull();
    }

    @Test
    void aSuccessfulCallRecordsRowCountAndLatency() {
        actAs(Role.ADMIN, TENANT_A);
        tools.call("query_sales_summary", ToolArguments.validFor("query_sales_summary"));

        AuditLog latest = auditLogRepository.findByToolNameOrderByIdDesc("query_sales_summary").get(0);
        assertThat(latest.getDecision()).isEqualTo("ALLOW");
        assertThat(latest.getOutcome()).isEqualTo("SUCCESS");
        assertThat(latest.getRowCount()).isEqualTo(3);   // three stores in tenant 7
        assertThat(latest.getLatencyMs()).isNotNull();
    }

    @Test
    void deniedCallsAreAuditedWithTheReason() {
        actAs(Role.STORE_MANAGER, TENANT_A);
        tools.call("get_order_detail", ToolArguments.validFor("get_order_detail"));

        AuditLog latest = auditLogRepository.findByToolNameOrderByIdDesc("get_order_detail").get(0);

        assertThat(latest.getDecision()).isEqualTo("DENY");
        assertThat(latest.getDenyReason()).isEqualTo("INSUFFICIENT_ROLE");
        assertThat(latest.getPrincipalRole()).isEqualTo("STORE_MANAGER");
        assertThat(latest.getTenantId()).isEqualTo(TENANT_A);
    }

    @Test
    void everyAuditRowIdentifiesWhoCalledWhatOnWhichTenant() {
        actAs(Role.ANALYST, TENANT_B, 99L, "analyst-b");
        tools.call("list_top_products", ToolArguments.validFor("list_top_products"));

        AuditLog latest = auditLogRepository.findByToolNameOrderByIdDesc("list_top_products").get(0);

        assertThat(latest.getPrincipalId()).isEqualTo(99L);
        assertThat(latest.getPrincipalRole()).isEqualTo("ANALYST");
        assertThat(latest.getTenantId()).isEqualTo(TENANT_B);
        assertThat(latest.getRiskTier()).isEqualTo("T1");
        assertThat(latest.getTraceId()).startsWith("trc_");
    }

    @Test
    void governanceAspectIsOrderedOutsideTheTransactionAdvisor() {
        // Ordering is a correctness property, not a style choice: inside the
        // transaction advisor, the pre-write audit row would be rolled back with
        // the business transaction and fail-closed would silently stop holding.
        Order order = AnnotationUtils.findAnnotation(GovernanceAspect.class, Order.class);

        assertThat(order).isNotNull();
        assertThat(order.value()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    @Test
    void auditRowsAccumulateRatherThanOverwrite() {
        actAs(Role.ADMIN, TENANT_A);
        List<AuditLog> before = auditLogRepository.findByToolNameOrderByIdDesc("check_inventory");
        tools.call("check_inventory", Map.of());
        tools.call("check_inventory", Map.of());
        List<AuditLog> after = auditLogRepository.findByToolNameOrderByIdDesc("check_inventory");

        assertThat(after).hasSize(before.size() + 2);
    }
}
