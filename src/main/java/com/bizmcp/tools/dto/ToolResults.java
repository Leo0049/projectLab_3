package com.bizmcp.tools.dto;

import com.bizmcp.governance.RowCountAware;
import com.bizmcp.masking.MaskStrategy;
import com.bizmcp.masking.Masked;

import java.math.BigDecimal;
import java.util.List;

import static com.bizmcp.governance.Role.ADMIN;
import static com.bizmcp.governance.Role.CS_LEAD;

/**
 * Tool result shapes.
 *
 * <p>Records rather than entities, so what crosses the MCP boundary is an
 * explicit, reviewable list of fields. Personal data carries {@link Masked}
 * here, which means the masking policy lives with the data rather than being
 * re-applied by every tool that happens to return it.
 *
 * <p>Every list-bearing result reports its own row count and whether it was
 * truncated, so the audit trail and the response cap stay honest.
 */
public final class ToolResults {

    private ToolResults() {
    }

    // ---------- T1: aggregates, no personal data ----------

    public record SalesSummaryRow(
            Long groupId,
            String groupKey,
            long orderCount,
            BigDecimal revenue
    ) {
    }

    public record SalesSummary(
            String groupBy,
            String startDate,
            String endDate,
            List<SalesSummaryRow> rows,
            BigDecimal totalRevenue,
            long totalOrders,
            boolean truncated
    ) implements RowCountAware {
        @Override
        public int rowCount() {
            return rows.size();
        }
    }

    public record InventoryRow(
            long productId,
            String name,
            int quantity,
            int lowWaterMark,
            boolean belowThreshold
    ) {
    }

    public record InventoryLevels(
            List<InventoryRow> rows,
            int belowThresholdCount,
            boolean truncated
    ) implements RowCountAware {
        @Override
        public int rowCount() {
            return rows.size();
        }
    }

    public record TopProductRow(
            long productId,
            String name,
            long totalQuantity,
            BigDecimal totalRevenue
    ) {
    }

    public record TopProducts(
            String startDate,
            String endDate,
            List<TopProductRow> rows,
            boolean truncated
    ) implements RowCountAware {
        @Override
        public int rowCount() {
            return rows.size();
        }
    }

    public record StatusCountRow(String status, long groupOrderCount) {
    }

    public record GroupOrderCounts(
            String startDate,
            String endDate,
            List<StatusCountRow> rows,
            long total,
            boolean truncated
    ) implements RowCountAware {
        @Override
        public int rowCount() {
            return rows.size();
        }
    }

    // ---------- T2: individual records, masked ----------

    public record GroupOrderRow(
            long groupOrderId,
            String title,
            String status,
            String storeName,
            String createdAt,
            long orderCount
    ) {
    }

    public record GroupOrderSearchResult(
            List<GroupOrderRow> rows,
            boolean truncated,
            String note
    ) implements RowCountAware {
        @Override
        public int rowCount() {
            return rows.size();
        }
    }

    public record OrderItemRow(
            String productName,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal subtotal
    ) {
    }

    /**
     * The one result that carries personal data. Masking is declared here and
     * applied by the governance chain before serialization; no tool code masks
     * anything by hand.
     */
    public record OrderDetail(
            long orderId,
            String status,
            BigDecimal totalAmount,
            String createdAt,
            String storeName,

            @Masked(strategy = MaskStrategy.NAME)
            String customerName,

            @Masked(strategy = MaskStrategy.PHONE)
            String phone,

            // Customer service needs the real address to resolve a delivery problem;
            // a store manager does not.
            @Masked(strategy = MaskStrategy.ADDRESS, unmaskFor = {CS_LEAD, ADMIN})
            String address,

            @Masked(strategy = MaskStrategy.EMAIL)
            String email,

            List<OrderItemRow> items
    ) implements RowCountAware {
        @Override
        public int rowCount() {
            return 1;
        }
    }
}
