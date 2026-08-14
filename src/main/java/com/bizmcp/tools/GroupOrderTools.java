package com.bizmcp.tools;

import com.bizmcp.governance.RiskTier;
import com.bizmcp.governance.Role;
import com.bizmcp.governance.ToolRisk;
import com.bizmcp.tools.dto.ToolResults;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** Group-order tools: one T1 aggregate, two T2 detail reads. */
@Component
public class GroupOrderTools {

    private final BusinessQueryService queryService;

    public GroupOrderTools(BusinessQueryService queryService) {
        this.queryService = queryService;
    }

    @McpTool(
            name = "count_group_orders",
            description = """
                    統計指定期間內的團購單數量，依狀態分組。
                    用於回答「這個月開了幾團」「有幾團還沒結單」這類問題。
                    只回傳各狀態的數量，不含團購單內容，也不含任何客戶個資。
                    狀態可選值：OPEN（開放中）、CLOSED（已結單）、COMPLETED（已完成）、CANCELLED（已取消）。
                    日期格式 YYYY-MM-DD，區間最長 90 天。
                    若需要看到每一團的標題與明細清單，請改用 search_group_orders。
                    """,
            generateOutputSchema = false
    )
    @ToolRisk(tier = RiskTier.T1, allow = {Role.STORE_MANAGER, Role.CS_LEAD, Role.ANALYST, Role.ADMIN})
    public Object countGroupOrders(
            @McpToolParam(description = "起始日期，格式 YYYY-MM-DD", required = true)
            LocalDate startDate,

            @McpToolParam(description = "結束日期（含當日），格式 YYYY-MM-DD", required = true)
            LocalDate endDate,

            @McpToolParam(description = "只統計此狀態；省略則統計全部狀態。"
                                        + "可選值 OPEN / CLOSED / COMPLETED / CANCELLED", required = false)
            String status
    ) {
        return queryService.groupOrderCounts(startDate, endDate, status);
    }

    @McpTool(
            name = "search_group_orders",
            description = """
                    依條件搜尋團購單清單，回傳每一團的標題、狀態、店家與訂單數。
                    用於回答「上週信義店開了哪些團」「找一下尾牙那團」這類問題。
                    回傳的是個別團購單紀錄，不含客戶姓名與聯絡方式。
                    單次最多回傳 200 筆；超過時會提示縮小範圍。
                    日期格式 YYYY-MM-DD，區間最長 90 天。
                    若只想知道數量而不需要清單，請改用 count_group_orders。
                    若要看某一筆訂單的完整明細，請改用 get_order_detail。
                    """,
            generateOutputSchema = false
    )
    @ToolRisk(tier = RiskTier.T2, allow = {Role.CS_AGENT, Role.CS_LEAD, Role.ADMIN})
    public Object searchGroupOrders(
            @McpToolParam(description = "起始日期，格式 YYYY-MM-DD", required = true)
            LocalDate startDate,

            @McpToolParam(description = "結束日期（含當日），格式 YYYY-MM-DD", required = true)
            LocalDate endDate,

            @McpToolParam(description = "團購單狀態；省略則不限。"
                                        + "可選值 OPEN / CLOSED / COMPLETED / CANCELLED", required = false)
            String status,

            @McpToolParam(description = "標題關鍵字，模糊比對；省略則不限", required = false)
            String keyword
    ) {
        return queryService.searchGroupOrders(status, startDate, endDate, keyword);
    }

    @McpTool(
            name = "get_order_detail",
            description = """
                    查詢單筆訂單的完整明細，包含店家、金額、品項清單與客戶聯絡資訊。
                    用於回答「這筆訂單買了什麼」「這張單的客戶怎麼聯絡」這類問題。

                    注意：本工具會回傳客戶個資，且個資欄位一律經過遮罩後才回傳
                    （姓名如 王○明、電話如 0912***678）。遮罩程度依呼叫者角色而定，
                    你看到的就是你被允許看到的全部，不要嘗試推測或還原被遮蔽的字元。

                    若查無資料會回傳 found=false，代表這筆訂單不存在或不屬於你的商戶。
                    若要找出符合條件的多筆訂單，請改用 search_group_orders。
                    """,
            generateOutputSchema = false
    )
    @ToolRisk(tier = RiskTier.T2, allow = {Role.CS_AGENT, Role.CS_LEAD, Role.ADMIN})
    public Object getOrderDetail(
            @McpToolParam(description = "訂單 ID", required = true)
            Long orderId
    ) {
        return queryService.orderDetail(orderId)
                .map(Object.class::cast)
                .orElseGet(() -> new NotFound(false,
                        "查詢成功，但找不到訂單 %d，或這筆訂單不屬於你的商戶。".formatted(orderId)));
    }

    /** Distinguishes "no data" from "error" (spec section 10). */
    public record NotFound(boolean found, String message) {
    }
}
