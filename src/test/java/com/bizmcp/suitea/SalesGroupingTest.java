package com.bizmcp.suitea;

import com.bizmcp.governance.Role;
import com.bizmcp.support.GovernanceTestBase;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * All three grouping dimensions of {@code query_sales_summary}
 * (spec section 6.2).
 *
 * <p>Each dimension is a separate SQL template, so testing only {@code STORE}
 * left two thirds of the tool unexercised. The last test here is the one that
 * matters most: the three groupings must agree on the total, because a user
 * asking the same question two ways and getting two answers is the kind of bug
 * that destroys trust in the whole system.
 */
class SalesGroupingTest extends GovernanceTestBase {

    private static final Map<String, Object> JANUARY = Map.of(
            "startDate", "2026-01-01", "endDate", "2026-01-31");

    private Map<String, Object> januaryGroupedBy(String dimension) {
        return Map.of("startDate", "2026-01-01", "endDate", "2026-01-31", "groupBy", dimension);
    }

    @Test
    void groupingByStoreReturnsOneRowPerStore() {
        actAs(Role.ADMIN, TENANT_A);
        String payload = tools.callForWirePayload("query_sales_summary", januaryGroupedBy("STORE"));

        assertThat(payload).contains("信義店", "大安店", "中山店");
        assertThat(payload).contains("450", "420", "80");
        assertThat(payload).contains("\"totalRevenue\":950");
    }

    @Test
    void groupingByDayReturnsOneRowPerTradingDay() {
        actAs(Role.ADMIN, TENANT_A);
        String payload = tools.callForWirePayload("query_sales_summary", januaryGroupedBy("DAY"));

        // Four days had completed orders: 01-05 (300), 01-06 (150),
        // 01-12 (420), 01-20 (80).
        assertThat(payload).contains("2026-01-05", "2026-01-06", "2026-01-12", "2026-01-20");
        assertThat(payload).contains("\"totalRevenue\":950");
        assertThat(payload).doesNotContain("2026-01-25");   // the cancelled order's day
        assertThat(payload).doesNotContain("2026-02-10");   // outside the window
    }

    @Test
    void groupingByProductReturnsOneRowPerProduct() {
        actAs(Role.ADMIN, TENANT_A);
        String payload = tools.callForWirePayload("query_sales_summary", januaryGroupedBy("PRODUCT"));

        assertThat(payload).contains("珍珠奶茶", "四季春", "紅茶拿鐵");
        assertThat(payload).contains("\"totalRevenue\":950");
        // 芋圓鮮奶 sold nothing in the window and must not appear with a zero row.
        assertThat(payload).doesNotContain("芋圓鮮奶");
    }

    @Test
    void allThreeGroupingsAgreeOnTheTotal() {
        actAs(Role.ADMIN, TENANT_A);

        // Same question, three ways to slice it. If order totals and line-item
        // subtotals ever drift apart, this is what catches it.
        for (String dimension : new String[]{"STORE", "DAY", "PRODUCT"}) {
            assertThat(tools.callForWirePayload("query_sales_summary", januaryGroupedBy(dimension)))
                    .as("total revenue grouped by %s", dimension)
                    .contains("\"totalRevenue\":950");
        }
    }

    @Test
    void topProductsRanksByQuantityAndExcludesCancelledOrders() {
        actAs(Role.ANALYST, TENANT_A);
        String payload = tools.callForWirePayload("list_top_products",
                Map.of("startDate", "2026-01-01", "endDate", "2026-01-31", "limit", 10));

        // 珍珠奶茶 qty 10, 四季春 qty 6, 紅茶拿鐵 qty 2. The cancelled order
        // would have added 5 and 5, which would flip nothing but must still be excluded.
        assertThat(payload.indexOf("珍珠奶茶")).isLessThan(payload.indexOf("四季春"));
        assertThat(payload.indexOf("四季春")).isLessThan(payload.indexOf("紅茶拿鐵"));
        assertThat(payload).contains("\"totalQuantity\":10");
    }

    @Test
    void theLimitArgumentTrimsTheRanking() {
        actAs(Role.ANALYST, TENANT_A);
        String payload = tools.callForWirePayload("list_top_products",
                Map.of("startDate", "2026-01-01", "endDate", "2026-01-31", "limit", 1));

        assertThat(payload).contains("珍珠奶茶");
        assertThat(payload).doesNotContain("四季春");
        assertThat(payload).contains("\"truncated\":true");
    }

    @Test
    void groupOrderCountsSplitByStatus() {
        actAs(Role.ADMIN, TENANT_A);
        String payload = tools.callForWirePayload("count_group_orders", JANUARY);

        // Tenant 7 in January: two COMPLETED, one OPEN, one CANCELLED.
        assertThat(payload).contains("COMPLETED", "OPEN", "CANCELLED");
        assertThat(payload).contains("\"total\":4");
    }

    @Test
    void groupOrderCountsCanFilterToASingleStatus() {
        actAs(Role.ADMIN, TENANT_A);
        String payload = tools.callForWirePayload("count_group_orders",
                Map.of("startDate", "2026-01-01", "endDate", "2026-01-31", "status", "OPEN"));

        assertThat(payload).contains("OPEN");
        assertThat(payload).contains("\"total\":1");
        assertThat(payload).doesNotContain("CANCELLED");
    }

    @Test
    void inventoryCanBeFilteredToLowStockOnly() {
        actAs(Role.STORE_MANAGER, TENANT_A);
        String payload = tools.callForWirePayload("check_inventory",
                Map.of("belowThreshold", true));

        // 四季春 (15/20) and 檸檬青茶 (8/20) are below their marks; 珍珠奶茶 (120/30) is not.
        assertThat(payload).contains("四季春", "檸檬青茶");
        assertThat(payload).doesNotContain("珍珠奶茶");
    }

    @Test
    void inventoryCanBeNarrowedToOneProduct() {
        actAs(Role.STORE_MANAGER, TENANT_A);
        String payload = tools.callForWirePayload("check_inventory",
                Map.of("productId", 1001L));

        assertThat(payload).contains("珍珠奶茶");
        assertThat(payload).doesNotContain("四季春");
    }
}
