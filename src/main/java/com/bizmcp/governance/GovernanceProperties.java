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
