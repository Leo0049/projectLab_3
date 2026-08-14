package com.bizmcp.tools;

import com.bizmcp.governance.BizPrincipal;
import com.bizmcp.governance.PrincipalResolver;
import com.bizmcp.query.QueryTemplateRegistry;
import com.bizmcp.query.QueryTemplateRegistry.QueryResult;
import com.bizmcp.query.Rows;
import com.bizmcp.tools.dto.GroupByDimension;
import com.bizmcp.tools.dto.ToolResults;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runs the named query templates and maps rows onto result records.
 *
 * <p>No tenant appears in any signature here either: it is read from the
 * principal at the point of execution, so there is no code path in which a
 * caller-supplied merchant could reach the SQL.
 */
@Service
public class BusinessQueryService {

    /** Guardrail matching the tool descriptions; also bounds the query plan. */
    private static final int MAX_RANGE_DAYS = 90;

    private final QueryTemplateRegistry templates;
    private final PrincipalResolver principalResolver;
    private final ZoneId businessZone;

    public BusinessQueryService(QueryTemplateRegistry templates,
                                PrincipalResolver principalResolver,
                                @Value("${bizmcp.business-zone:Asia/Taipei}") String businessZone) {
        this.templates = templates;
        this.principalResolver = principalResolver;
        this.businessZone = ZoneId.of(businessZone);
    }

    @Transactional(readOnly = true)
    public ToolResults.SalesSummary summarise(LocalDate startDate, LocalDate endDate, GroupByDimension groupBy) {
        validateRange(startDate, endDate);
        BizPrincipal principal = principalResolver.current();

        QueryResult result = templates.execute(groupBy.templateId(), principal,
                dateRangeParams(startDate, endDate));

        List<ToolResults.SalesSummaryRow> rows = result.rows().stream()
                .map(row -> new ToolResults.SalesSummaryRow(
                        Rows.longOrNull(row, "group_id"),
                        Rows.string(row, "group_key"),
                        Rows.longValue(row, "order_count"),
                        Rows.decimal(row, "revenue")))
                .toList();

        BigDecimal totalRevenue = rows.stream()
                .map(ToolResults.SalesSummaryRow::revenue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long totalOrders = rows.stream().mapToLong(ToolResults.SalesSummaryRow::orderCount).sum();

        return new ToolResults.SalesSummary(groupBy.name(), startDate.toString(), endDate.toString(),
                rows, totalRevenue, totalOrders, result.truncated());
    }

    @Transactional(readOnly = true)
    public ToolResults.InventoryLevels inventoryLevels(Long productId, Boolean belowThreshold) {
        BizPrincipal principal = principalResolver.current();

        Map<String, Object> params = new HashMap<>();
        params.put("productId", productId);
        params.put("belowThreshold", Boolean.TRUE.equals(belowThreshold));

        QueryResult result = templates.execute("inventory.levels", principal, params);

        List<ToolResults.InventoryRow> rows = result.rows().stream()
                .map(BusinessQueryService::toInventoryRow)
                .toList();

        int below = (int) rows.stream().filter(ToolResults.InventoryRow::belowThreshold).count();
        return new ToolResults.InventoryLevels(rows, below, result.truncated());
    }

    @Transactional(readOnly = true)
    public ToolResults.TopProducts topProducts(LocalDate startDate, LocalDate endDate, int limit) {
        validateRange(startDate, endDate);
        BizPrincipal principal = principalResolver.current();

        QueryResult result = templates.execute("products.top_selling", principal,
                dateRangeParams(startDate, endDate));

        List<ToolResults.TopProductRow> all = result.rows().stream()
                .map(row -> new ToolResults.TopProductRow(
                        Rows.longValue(row, "product_id"),
                        Rows.string(row, "name"),
                        Rows.longValue(row, "total_quantity"),
                        Rows.decimal(row, "total_revenue")))
                .toList();

        int effectiveLimit = Math.max(1, Math.min(limit, all.size()));
        List<ToolResults.TopProductRow> rows = all.subList(0, Math.min(effectiveLimit, all.size()));
        return new ToolResults.TopProducts(startDate.toString(), endDate.toString(), rows,
                result.truncated() || rows.size() < all.size());
    }

    @Transactional(readOnly = true)
    public ToolResults.GroupOrderCounts groupOrderCounts(LocalDate startDate, LocalDate endDate, String status) {
        validateRange(startDate, endDate);
        BizPrincipal principal = principalResolver.current();

        Map<String, Object> params = dateRangeParams(startDate, endDate);
        params.put("status", normaliseStatus(status));

        QueryResult result = templates.execute("group_orders.count_by_status", principal, params);

        List<ToolResults.StatusCountRow> rows = result.rows().stream()
                .map(row -> new ToolResults.StatusCountRow(
                        Rows.string(row, "status"),
                        Rows.longValue(row, "group_order_count")))
                .toList();

        long total = rows.stream().mapToLong(ToolResults.StatusCountRow::groupOrderCount).sum();
        return new ToolResults.GroupOrderCounts(startDate.toString(), endDate.toString(),
                rows, total, result.truncated());
    }

    @Transactional(readOnly = true)
    public ToolResults.GroupOrderSearchResult searchGroupOrders(String status,
                                                                LocalDate startDate,
                                                                LocalDate endDate,
                                                                String keyword) {
        validateRange(startDate, endDate);
        BizPrincipal principal = principalResolver.current();

        Map<String, Object> params = dateRangeParams(startDate, endDate);
        params.put("status", normaliseStatus(status));
        params.put("keyword", (keyword == null || keyword.isBlank()) ? null : keyword.trim());

        QueryResult result = templates.execute("group_orders.search", principal, params);

        List<ToolResults.GroupOrderRow> rows = result.rows().stream()
                .map(row -> new ToolResults.GroupOrderRow(
                        Rows.longValue(row, "group_order_id"),
                        Rows.string(row, "title"),
                        Rows.string(row, "status"),
                        Rows.string(row, "store_name"),
                        Rows.timestamp(row, "created_at"),
                        Rows.longValue(row, "order_count")))
                .toList();

        String note = result.truncated()
                ? "結果已達單次回傳上限 %d 筆，請縮小日期區間或加上關鍵字。".formatted(result.cap())
                : null;
        return new ToolResults.GroupOrderSearchResult(rows, result.truncated(), note);
    }

    @Transactional(readOnly = true)
    public Optional<ToolResults.OrderDetail> orderDetail(long orderId) {
        BizPrincipal principal = principalResolver.current();

        QueryResult header = templates.execute("orders.detail", principal, Map.of("orderId", orderId));
        if (header.isEmpty()) {
            // Also the answer when the order belongs to another tenant: the
            // template's tenant predicate excluded it, and we do not
            // distinguish "not yours" from "does not exist".
            return Optional.empty();
        }

        QueryResult itemRows = templates.execute("orders.detail_items", principal, Map.of("orderId", orderId));
        List<ToolResults.OrderItemRow> items = itemRows.rows().stream()
                .map(row -> new ToolResults.OrderItemRow(
                        Rows.string(row, "product_name"),
                        Rows.intValue(row, "quantity"),
                        Rows.decimal(row, "unit_price"),
                        Rows.decimal(row, "subtotal")))
                .toList();

        Map<String, Object> row = header.first();
        return Optional.of(new ToolResults.OrderDetail(
                Rows.longValue(row, "order_id"),
                Rows.string(row, "status"),
                Rows.decimal(row, "total_amount"),
                Rows.timestamp(row, "created_at"),
                Rows.string(row, "store_name"),
                Rows.string(row, "customer_name"),
                Rows.string(row, "customer_phone"),
                Rows.string(row, "customer_address"),
                Rows.string(row, "customer_email"),
                items));
    }

    private static ToolResults.InventoryRow toInventoryRow(Map<String, Object> row) {
        return new ToolResults.InventoryRow(
                Rows.longValue(row, "product_id"),
                Rows.string(row, "name"),
                Rows.intValue(row, "quantity"),
                Rows.intValue(row, "low_water_mark"),
                Rows.boolValue(row, "below_threshold"));
    }

    private Map<String, Object> dateRangeParams(LocalDate startDate, LocalDate endDate) {
        Map<String, Object> params = new HashMap<>();
        params.put("startDate", atStartOfDay(startDate));
        // Exclusive upper bound so the whole end day is included regardless of time.
        params.put("endDateExclusive", atStartOfDay(endDate.plusDays(1)));
        return params;
    }

    private OffsetDateTime atStartOfDay(LocalDate date) {
        return date.atStartOfDay(businessZone).toOffsetDateTime();
    }

    private String normaliseStatus(String status) {
        return (status == null || status.isBlank()) ? null : status.trim().toUpperCase();
    }

    /** Validation messages are written for the model to self-correct. */
    private void validateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("startDate 與 endDate 為必填，格式 YYYY-MM-DD。");
        }
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException(
                    "endDate (%s) 早於 startDate (%s)，請調換順序。".formatted(endDate, startDate));
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate);
        if (days > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException(
                    "查詢區間為 %d 天，超過上限 %d 天。請縮小區間後重試。".formatted(days, MAX_RANGE_DAYS));
        }
    }
}
