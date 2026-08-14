package com.bizmcp.suitea;

import com.bizmcp.governance.Role;
import com.bizmcp.support.GovernanceTestBase;
import com.bizmcp.support.ToolArguments;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Error messages are part of the interface (spec section 10): the model reads
 * them and tries to recover, so they must say what to do next — and must never
 * carry internals.
 */
class ErrorMessageContractTest extends GovernanceTestBase {

    @Test
    void aBadDateRangeTellsTheModelHowToFixIt() {
        actAs(Role.ADMIN, TENANT_A);
        String message = tools.callForWirePayload("query_sales_summary", Map.of(
                "startDate", "2026-01-31", "endDate", "2026-01-01", "groupBy", "STORE"));

        assertThat(message).contains("endDate").contains("startDate").contains("調換");
    }

    @Test
    void anOverlongRangeStatesTheLimit() {
        actAs(Role.ADMIN, TENANT_A);
        String message = tools.callForWirePayload("query_sales_summary", Map.of(
                "startDate", "2025-01-01", "endDate", "2026-01-01", "groupBy", "STORE"));

        assertThat(message).contains("90");
    }

    @Test
    void noDataIsReportedAsSuccessNotFailure() {
        actAs(Role.ADMIN, TENANT_A);
        // A window with no orders in it.
        String payload = tools.callForWirePayload("query_sales_summary", Map.of(
                "startDate", "2020-01-01", "endDate", "2020-01-31", "groupBy", "STORE"));

        // Distinguishing "nothing matched" from "something broke" stops the model
        // reporting an outage when the answer is simply zero.
        assertThat(payload).contains("untrusted_data");
        assertThat(payload).contains("\"rows\":[]");
    }

    @Test
    void aMissingRecordIsReportedAsAbsentNotAsAnError() {
        actAs(Role.CS_LEAD, TENANT_A);
        String payload = tools.callForWirePayload("get_order_detail", Map.of("orderId", 999_999L));

        assertThat(payload).contains("找不到訂單");
        assertThat(payload).contains("found");
    }

    @Test
    void anUnknownApprovalIdIsReportedAsAbsent() {
        actAs(Role.STORE_MANAGER, TENANT_A);
        String payload = tools.callForWirePayload("check_approval_status",
                ToolArguments.validFor("check_approval_status"));

        assertThat(payload).contains("找不到審批單");
    }

    @Test
    void internalFailuresSurfaceOnlyATraceId() {
        actAs(Role.ADMIN, TENANT_A);
        // An unparseable date reaches the framework's argument binding, which
        // fails with a message full of internal type detail.
        String message = tools.callForWirePayload("query_sales_summary", Map.of(
                "startDate", "上週一", "endDate", "2026-01-31", "groupBy", "STORE"));

        assertThat(message).doesNotContain("java.").doesNotContain("org.springframework");
        assertThat(message).doesNotContain("jdbc").doesNotContain("SQL");
    }
}
