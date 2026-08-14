package com.bizmcp.approval;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Executor for {@code adjust_inventory}. */
@Component
public class InventoryAdjustmentExecutor implements ApprovalExecutor {

    public static final String TOOL_NAME = "adjust_inventory";

    private final NamedParameterJdbcTemplate jdbc;

    public InventoryAdjustmentExecutor(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String toolName() {
        return TOOL_NAME;
    }

    @Override
    public Map<String, Object> buildPreview(long tenantId, Map<String, Object> arguments) {
        long productId = asLong(arguments.get("productId"));
        int delta = asInt(arguments.get("delta"));

        // Tenant-scoped: an approver must never see another merchant's stock.
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT p.name, i.quantity
                FROM inventory i
                JOIN products p ON p.product_id = i.product_id
                WHERE i.product_id = :productId AND i.merchant_id = :tenantId
                """, new MapSqlParameterSource()
                .addValue("productId", productId)
                .addValue("tenantId", tenantId));

        Map<String, Object> preview = new LinkedHashMap<>();
        if (rows.isEmpty()) {
            preview.put("error", "找不到品項 " + productId);
            return preview;
        }
        int before = ((Number) rows.get(0).get("quantity")).intValue();
        preview.put("productName", rows.get(0).get("name"));
        preview.put("before", before);
        preview.put("after", before + delta);
        preview.put("delta", delta);
        preview.put("affectedOrders", countOpenOrders(tenantId, productId));
        return preview;
    }

    private int countOpenOrders(long tenantId, long productId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT o.order_id)
                FROM orders o
                JOIN order_items oi ON oi.order_id = o.order_id
                WHERE oi.product_id = :productId
                  AND o.merchant_id = :tenantId
                  AND o.status = 'PENDING'
                """, new MapSqlParameterSource()
                .addValue("productId", productId)
                .addValue("tenantId", tenantId), Integer.class);
        return count == null ? 0 : count;
    }

    @Override
    public void execute(long tenantId, Map<String, Object> arguments) {
        long productId = asLong(arguments.get("productId"));
        int delta = asInt(arguments.get("delta"));

        // The tenant predicate is part of the write, not just the read: an
        // approval for tenant 7 can only ever move tenant 7's stock.
        int updated = jdbc.update("""
                UPDATE inventory
                SET quantity = quantity + :delta, updated_at = now()
                WHERE product_id = :productId
                  AND merchant_id = :tenantId
                  AND quantity + :delta >= 0
                """, new MapSqlParameterSource()
                .addValue("productId", productId)
                .addValue("tenantId", tenantId)
                .addValue("delta", delta));

        if (updated == 0) {
            throw new IllegalStateException(
                    "庫存調整失敗：品項 %d 不存在，或調整後庫存將為負數".formatted(productId));
        }
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }
}
