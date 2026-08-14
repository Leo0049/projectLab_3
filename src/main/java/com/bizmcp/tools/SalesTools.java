package com.bizmcp.tools;

import com.bizmcp.governance.RiskTier;
import com.bizmcp.governance.Role;
import com.bizmcp.governance.ToolRisk;
import com.bizmcp.tools.dto.GroupByDimension;
import com.bizmcp.tools.dto.ToolResults;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * T1 sales tools.
 *
 * <p>Descriptions follow spec section 6.3: what it does, when to use it,
 * exact parameter formats, an explicit statement about personal data, and a
 * pointer to the neighbouring tool. Tool descriptions are the main lever on
 * whether the model picks the right tool, so they are written as carefully as
 * the code.
 *
 * <p>Note what is absent from every signature: any merchant or tenant
 * parameter. There is no argument a model could set to read another
 * merchant's revenue.
 */
@Component
public class SalesTools {

    private final BusinessQueryService queryService;

    public SalesTools(BusinessQueryService queryService) {
        this.queryService = queryService;
    }

    @McpTool(
            name = "query_sales_summary",
            description = """
                    查詢指定期間的營收彙總，可依店家、品項或日期分組。
                    用於回答「上週賣多少」「哪家店表現最好」「這個月每天營收趨勢」這類問題。
                    只回傳聚合後的數字（訂單數與營收），不含任何客戶個資。
                    僅計入狀態為 COMPLETED 的訂單；取消的訂單不列入營收。
                    日期格式為 YYYY-MM-DD，起訖區間最長 90 天。
                    若需要單筆訂單的明細或客戶聯絡方式，請改用 get_order_detail。
                    若想知道賣最好的品項排行，請改用 list_top_products。
                    """,
            generateOutputSchema = false
    )
    @ToolRisk(tier = RiskTier.T1, allow = {Role.STORE_MANAGER, Role.CS_LEAD, Role.ANALYST, Role.ADMIN})
    public Object querySalesSummary(
            @McpToolParam(description = "起始日期，格式 YYYY-MM-DD，例如 2026-01-01", required = true)
            LocalDate startDate,

            @McpToolParam(description = "結束日期（含當日），格式 YYYY-MM-DD，例如 2026-01-31", required = true)
            LocalDate endDate,

            @McpToolParam(description = "分組維度，可選值：STORE（依店家）、PRODUCT（依品項）、DAY（依日期）",
                    required = true)
            GroupByDimension groupBy
    ) {
        // No merchantId in the signature: the tenant comes from the principal.
        return queryService.summarise(startDate, endDate, groupBy);
    }

    @McpTool(
            name = "list_top_products",
            description = """
                    查詢指定期間的熱銷品項排行，依銷售數量由高至低排序。
                    用於回答「最近什麼賣最好」「熱銷前五名是哪些」這類問題。
                    回傳品項名稱、售出數量與銷售金額，不含任何客戶個資。
                    僅計入狀態為 COMPLETED 的訂單。
                    日期格式為 YYYY-MM-DD，起訖區間最長 90 天。
                    若要知道這些品項現在的庫存夠不夠，請接著呼叫 check_inventory。
                    """,
            generateOutputSchema = false
    )
    @ToolRisk(tier = RiskTier.T1, allow = {Role.STORE_MANAGER, Role.CS_LEAD, Role.ANALYST, Role.ADMIN})
    public Object listTopProducts(
            @McpToolParam(description = "起始日期，格式 YYYY-MM-DD", required = true)
            LocalDate startDate,

            @McpToolParam(description = "結束日期（含當日），格式 YYYY-MM-DD", required = true)
            LocalDate endDate,

            @McpToolParam(description = "要回傳的品項數量，預設 10，最多 200", required = false)
            Integer limit
    ) {
        return queryService.topProducts(startDate, endDate, limit == null ? 10 : limit);
    }
}
