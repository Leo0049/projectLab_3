package com.bizmcp.approval;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Runs the approved write in its own transaction.
 *
 * <p>Separate bean, and {@code REQUIRES_NEW}, for a specific reason: if the
 * write ran in the caller's transaction, a failed statement would poison that
 * transaction and the caller could no longer record EXECUTION_FAILED against
 * the request. Isolating it means a failed write rolls back cleanly while the
 * status update still commits.
 */
@Component
public class ApprovalExecutionRunner {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void run(ApprovalExecutor executor, long tenantId, Map<String, Object> arguments) {
        executor.execute(tenantId, arguments);
    }
}
