package com.bizmcp.governance.ratelimit;

import com.bizmcp.governance.BizPrincipal;
import com.bizmcp.governance.RiskTier;

/**
 * Per-identity quota enforcement (spec section 6.1).
 *
 * <p>Two windows are checked: a minute quota that stops burst loops, and a day
 * quota that stops an agent from running 60 calls a minute all day. The day
 * window is the one that actually bounds cost.
 */
public interface RateLimiter {

    /**
     * Charges one call against the caller's quota for this tier.
     *
     * @throws com.bizmcp.governance.GovernanceExceptions.RateLimitExceededException
     *         if either window is exhausted
     */
    void consumeOrThrow(BizPrincipal principal, RiskTier tier);
}
