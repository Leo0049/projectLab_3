package com.bizmcp.governance;

/**
 * Tool risk tiers (spec section 6.1). The tier decides which governance
 * measures apply and which quota bucket the call is charged to.
 */
public enum RiskTier {

    /** Read-only aggregates. No individual records, so no masking needed. */
    T1(false),

    /** Read-only detail containing personal data. Masking applies. */
    T2(false),

    /** Writes. Never executed inline: they go through human approval. */
    T3(true);

    private final boolean requiresApproval;

    RiskTier(boolean requiresApproval) {
        this.requiresApproval = requiresApproval;
    }

    public boolean requiresApproval() {
        return requiresApproval;
    }
}
