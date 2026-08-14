package com.bizmcp.tools;

import com.bizmcp.governance.RiskTier;
import com.bizmcp.governance.Role;
import com.bizmcp.governance.ToolRisk;
import com.bizmcp.tools.dto.ToolResults;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** Inventory tools: one T1 read, one T3 write that must be approved. */
@Component
public class InventoryTools {

    private final BusinessQueryService queryService;

    public InventoryTools(BusinessQueryService queryService) {
        this.queryService = queryService;
    }

    @McpTool(
            name = "check_inventory",
            description = """
                    查詢目前庫存水位，可查單一品項或只列出低於安全水位的品項。
                    用於回答「還有多少珍珠」「哪些原料快沒了」這類問題。
                    回傳品項庫存數量與安全水位，不含任何客戶個資。
                    兩個參數都可省略：全部省略時回傳所有品項。
                    這是唯讀工具，不會改變庫存。若要調整庫存數量，請改用 adjust_inventory。
                    """,
            generateOutputSchema = false
    )
    @ToolRisk(tier = RiskTier.T1, allow = {Role.STORE_MANAGER, Role.CS_LEAD, Role.ADMIN})
    public Object checkInventory(
            @McpToolParam(description = "品項 ID；省略則查全部品項", required = false)
            Long productId,

            @McpToolParam(description = "設為 true 時只回傳低於安全水位的品項；預設 false", required = false)
            Boolean belowThreshold
    ) {
        return queryService.inventoryLevels(productId, belowThreshold);
    }

    @McpTool(
            name = "adjust_inventory",
            description = """
                    調整某品項的庫存數量並記錄原因。
                    用於盤點差異、報廢、進貨補登這類需要更動庫存的情境。

                    重要：這個工具「不會」立即生效。呼叫後只會建立一張待審批單，
                    回傳 status=PENDING_APPROVAL 與 approvalId，並附上異動前後的預覽。
                    必須由人到 /approvals 核准後才會真正執行。
                    請把 approvalId 告訴使用者，並在使用者表示已核准後，
                    用 check_approval_status 查詢真正的結果；
                    在查到 EXECUTED 之前，不要宣稱庫存已經調整完成。

                    delta 為增減量：正數增加、負數減少（例如 -50 表示減少 50）。
                    """,
            generateOutputSchema = false
    )
    @ToolRisk(tier = RiskTier.T3, allow = {Role.STORE_MANAGER, Role.ADMIN})
    public Object adjustInventory(
            @McpToolParam(description = "要調整的品項 ID", required = true)
            Long productId,

            @McpToolParam(description = "增減量，正數增加、負數減少，例如 -50", required = true)
            Integer delta,

            @McpToolParam(description = "調整原因，例如「盤點差異」「破損報廢」", required = true)
            String reason
    ) {
        // Unreachable in normal operation: the governance chain intercepts every
        // T3 call and parks it for approval, so this body never runs. If it ever
        // does, the approval gate has been bypassed and refusing to write is the
        // only safe response.
        throw new IllegalStateException(
                "adjust_inventory 必須經過審批閘門執行，直接呼叫已被拒絕。");
    }
}
