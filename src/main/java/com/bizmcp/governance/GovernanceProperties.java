package com.bizmcp.governance;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

@ConfigurationProperties(prefix = "bizmcp.governance")
public class GovernanceProperties {

    /** Hard cap on rows returned by any single tool call (spec section 9.4). */
    private int maxRows = 200;

    /** Rough token ceiling for a single response; estimated from characters. */
    private int maxResponseTokens = 8000;

    /** How long a pending approval stays actionable (spec section 8.1). */
    private Duration approvalTtl = Duration.ofMinutes(30);

    /** Retries allowed after EXECUTION_FAILED before ABANDONED (spec section 8.2). */
    private int maxApprovalRetries = 3;

    private Map<RiskTier, Quota> rateLimits = new EnumMap<>(RiskTier.class);

    /**
     * Which quota store to use.
     *
     * <p>Chosen explicitly rather than inferred from whether Redis happens to
     * be on the classpath. Guessing here is dangerous in one direction: falling
     * back to per-instance counters when shared counters were intended
     * multiplies every quota by the number of instances, quietly.
     */
    private RateLimitBackend rateLimitBackend = RateLimitBackend.REDIS;

    public enum RateLimitBackend {
        /** Shared across instances. The only correct choice for a real deployment. */
        REDIS,
        /** Per-instance counters, for tests and single-node local runs. */
        MEMORY
    }

    public RateLimitBackend getRateLimitBackend() {
        return rateLimitBackend;
    }

    public void setRateLimitBackend(RateLimitBackend rateLimitBackend) {
        this.rateLimitBackend = rateLimitBackend;
    }

    public static class Quota {
        private int perMinute;
        private int perDay;

        public int getPerMinute() {
            return perMinute;
        }

        public void setPerMinute(int perMinute) {
            this.perMinute = perMinute;
        }

        public int getPerDay() {
            return perDay;
        }

        public void setPerDay(int perDay) {
            this.perDay = perDay;
        }
    }

    public Quota quotaFor(RiskTier tier) {
        Quota quota = rateLimits.get(tier);
        if (quota == null) {
            throw new IllegalStateException("no rate limit configured for tier " + tier);
        }
        return quota;
    }

    public int getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(int maxRows) {
        this.maxRows = maxRows;
    }

    public int getMaxResponseTokens() {
        return maxResponseTokens;
    }

    public void setMaxResponseTokens(int maxResponseTokens) {
        this.maxResponseTokens = maxResponseTokens;
    }

    public Duration getApprovalTtl() {
        return approvalTtl;
    }

    public void setApprovalTtl(Duration approvalTtl) {
        this.approvalTtl = approvalTtl;
    }

    public int getMaxApprovalRetries() {
        return maxApprovalRetries;
    }

    public void setMaxApprovalRetries(int maxApprovalRetries) {
        this.maxApprovalRetries = maxApprovalRetries;
    }

    public Map<RiskTier, Quota> getRateLimits() {
        return rateLimits;
    }

    public void setRateLimits(Map<RiskTier, Quota> rateLimits) {
        this.rateLimits = rateLimits;
    }
}
