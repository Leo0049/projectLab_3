package com.bizmcp.tools;

import com.bizmcp.approval.ApprovalService;
import com.bizmcp.governance.PrincipalResolver;
import com.bizmcp.governance.RiskTier;
import com.bizmcp.governance.Role;
import com.bizmcp.governance.ToolRisk;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Lets the model find out what actually happened to a write it requested
 * (spec section 8.1).
 *
 * <p>Without this the conversation dead-ends: the model returns
 * PENDING_APPROVAL and then has no way to learn the outcome, so it either goes
 * silent or guesses that it worked. Guessing is the bad case — this tool is
 * what lets it say "approved, but the write failed" truthfully.
 */
@Component
public class ApprovalTools {

    private final ApprovalService approvalService;
    private final PrincipalResolver principalResolver;

    public ApprovalTools(ApprovalService approvalService, PrincipalResolver principalResolver) {
        this.approvalService = approvalService;
        this.principalResolver = principalResolver;
    }

    @McpTool(
            name = "check_approval_status",
            description = """
                    查詢一張待審批單目前的狀態與執行結果。
                    在你呼叫過任何寫入型工具（例如 adjust_inventory）之後，
                    或使用者說「我核准了」的時候，用這個工具確認真正的結果。

                    可能的狀態與意義：
                    - PENDING：還沒有人核准，操作尚未生效
                    - APPROVED：已核准、執行中
                    - EXECUTED：已成功執行，資料已變更
                    - EXECUTION_FAILED：已核准但執行失敗，資料「沒有」變更，可請審批者重試
                    - REJECTED：審批者駁回，未執行
                    - EXPIRED：逾時作廢，未執行
                    - ABANDONED：重試次數用盡，已放棄

                    請完全依照回傳的狀態向使用者說明。
                    只有在狀態為 EXECUTED 時才能說操作已完成；
                    若為 EXECUTION_FAILED，必須明確告訴使用者操作沒有成功以及失敗原因。
                    這是唯讀工具，不會改變任何資料，也不會核准任何東西。
                    """,
            generateOutputSchema = false
    )
    @ToolRisk(tier = RiskTier.T1,
            allow = {Role.STORE_MANAGER, Role.CS_LEAD, Role.APPROVER, Role.ADMIN})
    public Object checkApprovalStatus(
            @McpToolParam(description = "審批單編號，格式 apr_xxxxxxxx", required = true)
            String approvalId
    ) {
        long tenantId = principalResolver.current().tenantId();
        return approvalService.find(approvalId, tenantId)
                .map(request -> (Object) approvalService.toView(request))
                .orElseGet(() -> new NotFound(approvalId, false,
                        "查詢成功，但找不到審批單 %s，或它不屬於你的商戶。".formatted(approvalId)));
    }

    public record NotFound(String approvalId, boolean found, String message) {
    }
}
