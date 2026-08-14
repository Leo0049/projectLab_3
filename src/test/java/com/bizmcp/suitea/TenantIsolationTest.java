package com.bizmcp.suitea;

import com.bizmcp.governance.Role;
import com.bizmcp.support.GovernanceTestBase;
import com.bizmcp.support.ToolArguments;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Row-level tenant isolation (spec sections 3.2, 7.3).
 *
 * <p>The seed data puts real rows on both sides of the boundary, so these
 * assertions fail if isolation breaks — unlike a test against an empty second
 * tenant, which would pass either way.
 */
class TenantIsolationTest extends GovernanceTestBase {

    @Test
    void revenueIsScopedToTheCallersTenant() {
        actAs(Role.ADMIN, TENANT_A);
        String tenantA = tools.callForWirePayload("query_sales_summary",
                ToolArguments.validFor("query_sales_summary"));

        // Tenant 7's stores, and its numbers only.
        assertThat(tenantA).contains("信義店", "大安店", "中山店");
        assertThat(tenantA).doesNotContain("板橋店", "新莊店");

        actAs(Role.ADMIN, TENANT_B);
        String tenantB = tools.callForWirePayload("query_sales_summary",
                ToolArguments.validFor("query_sales_summary"));

        assertThat(tenantB).contains("板橋店");
        assertThat(tenantB).doesNotContain("信義店", "大安店", "中山店");
    }

    @Test
    void revenueTotalsDifferPerTenant() {
        actAs(Role.ADMIN, TENANT_A);
        // 450 + 420 + 80, cancelled and out-of-window orders excluded
        assertThat(tools.callForWirePayload("query_sales_summary",
                ToolArguments.validFor("query_sales_summary"))).contains("950");

        actAs(Role.ADMIN, TENANT_B);
        assertThat(tools.callForWirePayload("query_sales_summary",
                ToolArguments.validFor("query_sales_summary"))).contains("1000");
    }

    @Test
    void inventoryIsScopedToTheCallersTenant() {
        actAs(Role.ADMIN, TENANT_A);
        String tenantA = tools.callForWirePayload("check_inventory", Map.of());
        assertThat(tenantA).contains("珍珠奶茶");
        assertThat(tenantA).doesNotContain("烏龍拿鐵", "蜂蜜綠茶");

        actAs(Role.ADMIN, TENANT_B);
        String tenantB = tools.callForWirePayload("check_inventory", Map.of());
        assertThat(tenantB).contains("烏龍拿鐵");
        assertThat(tenantB).doesNotContain("珍珠奶茶");
    }

    @Test
    void anotherTenantsOrderIsNotReadable() {
        actAs(Role.ADMIN, TENANT_A);
        String payload = tools.callForWirePayload("get_order_detail",
                Map.of("orderId", ToolArguments.ORDER_TENANT_B));

        // Reported as absent rather than forbidden: existence is itself
        // information about another tenant.
        assertThat(payload).contains("找不到訂單");
        assertThat(payload).doesNotContain("林建宏");
    }

    @Test
    void ownTenantsOrderIsReadable() {
        actAs(Role.ADMIN, TENANT_A);
        String payload = tools.callForWirePayload("get_order_detail",
                Map.of("orderId", ToolArguments.ORDER_TENANT_A));
        assertThat(payload).doesNotContain("找不到訂單");
        assertThat(payload).contains("信義店");
    }

    @Test
    void groupOrderSearchIsScopedToTheCallersTenant() {
        actAs(Role.CS_LEAD, TENANT_A);
        String payload = tools.callForWirePayload("search_group_orders",
                ToolArguments.validFor("search_group_orders"));
        assertThat(payload).contains("信義店");
        assertThat(payload).doesNotContain("板橋店");
    }

    @Test
    void topProductsAreScopedToTheCallersTenant() {
        actAs(Role.ANALYST, TENANT_A);
        String payload = tools.callForWirePayload("list_top_products",
                ToolArguments.validFor("list_top_products"));
        assertThat(payload).contains("珍珠奶茶");
        assertThat(payload).doesNotContain("烏龍拿鐵");
    }

    @Test
    void aTenantArgumentSuppliedByTheModelIsIgnored() {
        actAs(Role.ADMIN, TENANT_A);

        // Simulates a prompt-injected attempt to widen scope. The tool schema has
        // no such parameter, so the extra key cannot reach the query at all.
        Map<String, Object> injected = new LinkedHashMap<>(
                ToolArguments.validFor("query_sales_summary"));
        injected.put("merchantId", TENANT_B);
        injected.put("tenantId", TENANT_B);
        injected.put("__tenant", TENANT_B);

        String payload = tools.callForWirePayload("query_sales_summary", injected);

        assertThat(payload).contains("信義店");
        assertThat(payload).doesNotContain("板橋店");
    }
}
