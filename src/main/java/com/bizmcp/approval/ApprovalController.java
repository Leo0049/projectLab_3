package com.bizmcp.approval;

import com.bizmcp.security.BizUserDetails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.ZoneId;
import java.util.List;

/**
 * The approval console (spec section 8.1). Deliberately one page: the front end
 * is not the point of this project, but the page has to show the approver the
 * impact preview, not just a parameter dump, or the approval is a rubber stamp.
 *
 * <p>Scoped to the approver's own tenant, so an approver at one merchant never
 * sees another merchant's pending writes.
 */
@Controller
@RequestMapping("/approvals")
public class ApprovalController {

    private final ApprovalService approvalService;
    private final ZoneId businessZone;

    public ApprovalController(ApprovalService approvalService,
                              @Value("${bizmcp.business-zone:Asia/Taipei}") String businessZone) {
        this.approvalService = approvalService;
        this.businessZone = ZoneId.of(businessZone);
    }

    @GetMapping
    public String list(@AuthenticationPrincipal BizUserDetails user, Model model) {
        List<ApprovalRowView> requests = approvalService.consoleRows(user.tenantId(), businessZone);
        model.addAttribute("requests", requests);
        model.addAttribute("displayName", user.displayName());
        return "approvals";
    }

    @PostMapping("/{id}/approve")
    public String approve(@PathVariable String id,
                          @AuthenticationPrincipal BizUserDetails user,
                          RedirectAttributes redirectAttributes) {
        return act(redirectAttributes, () -> {
            ApprovalRequest result = approvalService.approve(id, user.userId(), user.tenantId());
            return switch (result.getStatus()) {
                case EXECUTED -> "審批單 %s 已核准並執行成功。".formatted(id);
                case EXECUTION_FAILED -> "審批單 %s 已核准，但執行失敗：%s"
                        .formatted(id, result.getFailureReason());
                case ABANDONED -> "審批單 %s 重試次數已用盡，已放棄。".formatted(id);
                default -> "審批單 %s 目前狀態為 %s。".formatted(id, result.getStatus());
            };
        });
    }

    @PostMapping("/{id}/reject")
    public String reject(@PathVariable String id,
                         @RequestParam(defaultValue = "未提供原因") String reason,
                         @AuthenticationPrincipal BizUserDetails user,
                         RedirectAttributes redirectAttributes) {
        return act(redirectAttributes, () -> {
            approvalService.reject(id, user.userId(), user.tenantId(), reason);
            return "審批單 %s 已駁回。".formatted(id);
        });
    }

    @PostMapping("/{id}/retry")
    public String retry(@PathVariable String id,
                        @AuthenticationPrincipal BizUserDetails user,
                        RedirectAttributes redirectAttributes) {
        return act(redirectAttributes, () -> {
            ApprovalRequest result = approvalService.retry(id, user.userId(), user.tenantId());
            return "審批單 %s 重試後狀態為 %s。".formatted(id, result.getStatus());
        });
    }

    private String act(RedirectAttributes redirectAttributes, Action action) {
        try {
            redirectAttributes.addFlashAttribute("message", action.run());
        } catch (RuntimeException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/approvals";
    }

    @FunctionalInterface
    private interface Action {
        String run();
    }
}
