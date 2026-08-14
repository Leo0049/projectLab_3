package com.bizmcp.suitea;

import com.bizmcp.governance.Role;
import com.bizmcp.support.GovernanceTestBase;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the Spike 1 failure mode: if governance proxying ever hides @McpTool
 * from the provider again, the server would advertise nothing and every other
 * test would pass vacuously. This is the canary for that.
 */
class ToolDiscoveryTest extends GovernanceTestBase {

    @Test
    void allEightToolsAreRegistered() {
        assertThat(tools.toolNames()).containsExactlyInAnyOrder(
                "query_sales_summary",
                "check_inventory",
                "list_top_products",
                "count_group_orders",
                "check_approval_status",
                "search_group_orders",
                "get_order_detail",
                "adjust_inventory");
    }

    @Test
    void governanceChainRunsForToolsInvokedOverTheProtocol() {
        actAs(Role.ADMIN, TENANT_A);
        String payload = tools.callForWirePayload("check_inventory", Map.of());
        // The envelope is added by the governance chain, so its presence proves
        // the aspect ran on a real protocol-level invocation.
        assertThat(payload).contains("untrusted_data");
    }
}
