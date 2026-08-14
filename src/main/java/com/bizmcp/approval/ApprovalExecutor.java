package com.bizmcp.approval;

import java.util.Map;

/**
 * Performs the real write once a human has approved it. One implementation per
 * T3 tool; {@link ApprovalService} looks them up by tool name.
 */
public interface ApprovalExecutor {

    /** The {@code @McpTool} name this executor serves. */
    String toolName();

    /**
     * Builds the impact preview shown to the approver before they decide.
     * An approver looking at raw parameters is guessing; showing "120 -> 70"
     * is what makes the approval meaningful (spec section 8.1).
     */
    Map<String, Object> buildPreview(long tenantId, Map<String, Object> arguments);

    /**
     * Applies the change. Runs in its own transaction, so a failure here rolls
     * back cleanly and the request can be marked EXECUTION_FAILED.
     */
    void execute(long tenantId, Map<String, Object> arguments);
}
