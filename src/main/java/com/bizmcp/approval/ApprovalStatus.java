package com.bizmcp.approval;

import java.util.Set;

/**
 * Approval lifecycle (spec section 8.2).
 *
 * <p>v1.1 had no state for "approved, then the execution failed", which left
 * such requests stuck in APPROVED with a null {@code executed_at} and nobody
 * able to tell whether to retry or give up. {@link #EXECUTION_FAILED} and
 * {@link #ABANDONED} close that gap.
 */
public enum ApprovalStatus {

    /** Waiting for a human decision. */
    PENDING,

    /** Approved and about to run. */
    APPROVED,

    /** Ran successfully. Terminal. */
    EXECUTED,

    /**
     * Approved but the write failed. Retryable by a human, never automatically:
     * what was approved was the preview, and the world may have moved since.
     */
    EXECUTION_FAILED,

    /** Refused by an approver. Terminal. */
    REJECTED,

    /** TTL elapsed before anyone decided. Terminal. */
    EXPIRED,

    /** Retry budget exhausted. Terminal. */
    ABANDONED;

    private static final Set<ApprovalStatus> TERMINAL =
            Set.of(EXECUTED, REJECTED, EXPIRED, ABANDONED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public boolean canTransitionTo(ApprovalStatus target) {
        return switch (this) {
            case PENDING -> target == APPROVED || target == REJECTED || target == EXPIRED;
            case APPROVED -> target == EXECUTED || target == EXECUTION_FAILED;
            case EXECUTION_FAILED -> target == EXECUTED || target == EXECUTION_FAILED || target == ABANDONED;
            case EXECUTED, REJECTED, EXPIRED, ABANDONED -> false;
        };
    }
}
