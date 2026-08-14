package com.bizmcp.support;

import java.util.LinkedHashMap;
import java.util.Map;

/** Valid argument sets for each tool, so RBAC tests fail on policy, not on bad input. */
public final class ToolArguments {

    /** The deterministic January window seeded for Suite A. */
    public static final String START = "2026-01-01";
    public static final String END = "2026-01-31";

    /** Order 90001 belongs to tenant 7 and customer 王小明. */
    public static final long ORDER_TENANT_A = 90001L;
    /** Order 90101 belongs to tenant 9. */
    public static final long ORDER_TENANT_B = 90101L;
    /** 珍珠奶茶, seeded at quantity 120 for the spec section 8.1 demo. */
    public static final long PRODUCT_PEARL = 1001L;

    private ToolArguments() {
    }

    public static Map<String, Object> validFor(String toolName) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        switch (toolName) {
            case "query_sales_summary" -> {
                arguments.put("startDate", START);
                arguments.put("endDate", END);
                arguments.put("groupBy", "STORE");
            }
            case "list_top_products" -> {
                arguments.put("startDate", START);
                arguments.put("endDate", END);
                arguments.put("limit", 5);
            }
            case "count_group_orders" -> {
                arguments.put("startDate", START);
                arguments.put("endDate", END);
            }
            case "search_group_orders" -> {
                arguments.put("startDate", START);
                arguments.put("endDate", END);
            }
            case "check_inventory" -> {
                // no arguments: returns every product for the caller's tenant
            }
            case "get_order_detail" -> arguments.put("orderId", ORDER_TENANT_A);
            case "check_approval_status" -> arguments.put("approvalId", "apr_does_not_exist");
            case "adjust_inventory" -> {
                arguments.put("productId", PRODUCT_PEARL);
                arguments.put("delta", -1);
                arguments.put("reason", "suite-a fixture");
            }
            default -> throw new IllegalArgumentException("no fixture arguments for tool " + toolName);
        }
        return arguments;
    }
}
