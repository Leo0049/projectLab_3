package com.bizmcp.suitea;

import com.bizmcp.governance.Role;
import com.bizmcp.support.GovernanceTestBase;
import com.bizmcp.support.ToolArguments;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tool-level RBAC (spec section 5.3), one case per tool/role pair that matters.
 *
 * <p>Every case goes through the MCP tool specification, not the service, so
 * what is asserted is what a client would actually get.
 */
class RbacMatrixTest extends GovernanceTestBase {

    @ParameterizedTest(name = "{1} may call {0}")
    @CsvSource({
            // T1 aggregates: broadly available, but never to AUDITOR
            "query_sales_summary,  STORE_MANAGER",
            "query_sales_summary,  ANALYST",
            "query_sales_summary,  ADMIN",
            "check_inventory,      STORE_MANAGER",
            "check_inventory,      CS_LEAD",
            "list_top_products,    ANALYST",
            "count_group_orders,   STORE_MANAGER",
            "check_approval_status,APPROVER",
            // T2 detail: customer-facing roles only
            "search_group_orders,  CS_AGENT",
            "search_group_orders,  CS_LEAD",
            "get_order_detail,     CS_AGENT",
            "get_order_detail,     CS_LEAD",
            "get_order_detail,     ADMIN",
            // T3 write: parked for approval, but the caller must still be allowed
            "adjust_inventory,     STORE_MANAGER",
            "adjust_inventory,     ADMIN"
    })
    void allowedCombinationsSucceed(String toolName, Role role) {
        actAs(role, TENANT_A);
        McpSchema.CallToolResult result = tools.call(toolName, ToolArguments.validFor(toolName));
        assertThat(tools.isError(result))
                .as("%s should be callable by %s", toolName, role)
                .isFalse();
    }

    @ParameterizedTest(name = "{1} may not call {0}")
    @CsvSource({
            // AUDITOR reads the audit trail, not the business data
            "query_sales_summary,  AUDITOR",
            "check_inventory,      AUDITOR",
            // A store manager must not reach customer records (spec section 13, 1:00)
            "get_order_detail,     STORE_MANAGER",
            "search_group_orders,  STORE_MANAGER",
            "get_order_detail,     ANALYST",
            // Analysts and CS staff cannot move stock
            "adjust_inventory,     ANALYST",
            "adjust_inventory,     CS_LEAD",
            "adjust_inventory,     CS_AGENT",
            // Front-line agents have no aggregate revenue access
            "query_sales_summary,  CS_AGENT",
            // Approvers approve; they do not browse the business
            "get_order_detail,     APPROVER"
    })
    void deniedCombinationsAreRefused(String toolName, Role role) {
        actAs(role, TENANT_A);
        McpSchema.CallToolResult result = tools.call(toolName, ToolArguments.validFor(toolName));

        assertThat(tools.isError(result))
                .as("%s must be denied for %s", toolName, role)
                .isTrue();
        assertThat(textOf(result)).contains("角色");
    }

    @Test
    void denialExplainsWhichRoleIsNeededWithoutLeakingData() {
        actAs(Role.STORE_MANAGER, TENANT_A);
        McpSchema.CallToolResult result =
                tools.call("get_order_detail", ToolArguments.validFor("get_order_detail"));

        String message = textOf(result);
        // Actionable for the model (spec section 10)...
        assertThat(message).contains("CS_LEAD");
        // ...but it must not carry the record the caller was refused.
        assertThat(message).doesNotContain("王").doesNotContain("0912");
    }

    private String textOf(McpSchema.CallToolResult result) {
        StringBuilder text = new StringBuilder();
        result.content().forEach(content -> {
            if (content instanceof McpSchema.TextContent textContent) {
                text.append(textContent.text());
            }
        });
        return text.toString();
    }
}
