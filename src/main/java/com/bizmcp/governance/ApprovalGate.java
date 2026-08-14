package com.bizmcp.governance;

/**
 * Intercepts T3 (write) calls. Implementations must not perform the write:
 * they record the intent and hand back a pending result for the model to
 * report (spec section 8.1).
 */
public interface ApprovalGate {

    /**
     * Parks a write request for human approval.
     *
     * @param auditId the already-committed audit row for this call
     * @return the payload returned to the model in place of the write result
     */
    Object enqueue(Long auditId, BizPrincipal principal, ToolCall call);
}
