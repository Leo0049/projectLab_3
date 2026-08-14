package com.bizmcp.approval;

import com.bizmcp.governance.ApprovalGate;
import com.bizmcp.governance.BizPrincipal;
import com.bizmcp.governance.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parks T3 calls for human approval (spec section 8.1).
 *
 * <p>The write is never attempted here. The tool returns PENDING_APPROVAL with
 * an impact preview, and nothing changes until a person approves it — which is
 * the property that makes "the model got talked into it" survivable.
 */
@Component
public class DefaultApprovalGate implements ApprovalGate {

    private static final Logger log = LoggerFactory.getLogger(DefaultApprovalGate.class);

    private final ApprovalService approvalService;

    public DefaultApprovalGate(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @Override
    public Object enqueue(Long auditId, BizPrincipal principal, ToolCall call) {
        ApprovalExecutor executor = approvalService.executorFor(call.toolName());
        Map<String, Object> arguments = normalise(call.arguments());
        Map<String, Object> preview = executor.buildPreview(principal.tenantId(), arguments);

        String approvalId = ApprovalService.newApprovalId();
        approvalService.create(approvalId, auditId, principal.tenantId(), principal.userId(),
                call.toolName(), arguments, preview);

        log.info("parked {} as {} for tenant {} (requested by {})",
                call.toolName(), approvalId, principal.tenantId(), principal.username());

        return ApprovalViews.PendingApproval.of(approvalId, preview);
    }

    /**
     * Arguments are persisted as JSON and read back by the executor, so reduce
     * them to JSON-native types here rather than relying on reflective
     * serialization of arbitrary parameter types.
     */
    private Map<String, Object> normalise(Map<String, Object> arguments) {
        Map<String, Object> normalised = new LinkedHashMap<>();
        arguments.forEach((name, value) -> normalised.put(name, switch (value) {
            case null -> null;
            case Number number -> number;
            case Boolean bool -> bool;
            case String text -> text;
            default -> String.valueOf(value);
        }));
        return normalised;
    }
}
