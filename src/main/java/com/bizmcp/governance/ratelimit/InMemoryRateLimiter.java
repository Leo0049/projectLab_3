package com.bizmcp.governance.ratelimit;

import com.bizmcp.governance.BizPrincipal;
import com.bizmcp.governance.GovernanceExceptions.RateLimitExceededException;
import com.bizmcp.governance.GovernanceProperties;
import com.bizmcp.governance.RiskTier;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single-node quota counter used when Redis is not configured (tests, and the
 * no-Redis local profile). Same window semantics as {@link RedisRateLimiter};
 * it simply cannot coordinate across instances.
 */
public class InMemoryRateLimiter implements RateLimiter {

    private final GovernanceProperties properties;
    private final Clock clock;
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    public InMemoryRateLimiter(GovernanceProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void consumeOrThrow(BizPrincipal principal, RiskTier tier) {
        GovernanceProperties.Quota quota = properties.quotaFor(tier);
        Instant now = clock.instant();

        long epochMinute = now.getEpochSecond() / 60;
        long epochDay = now.getEpochSecond() / 86_400;

        String minuteKey = key(principal, tier, "m", epochMinute);
        String dayKey = key(principal, tier, "d", epochDay);

        long minuteCount = counters.computeIfAbsent(minuteKey, k -> new AtomicLong()).incrementAndGet();
        if (minuteCount > quota.getPerMinute()) {
            throw new RateLimitExceededException("分鐘", 60 - (now.getEpochSecond() % 60));
        }

        long dayCount = counters.computeIfAbsent(dayKey, k -> new AtomicLong()).incrementAndGet();
        if (dayCount > quota.getPerDay()) {
            throw new RateLimitExceededException("每日", 86_400 - (now.getEpochSecond() % 86_400));
        }
    }

    private String key(BizPrincipal principal, RiskTier tier, String window, long bucket) {
        return "rl:%d:%d:%s:%s:%d".formatted(
                principal.tenantId(), principal.userId(), tier, window, bucket);
    }

    /** Test hook: clears all windows. */
    public void reset() {
        counters.clear();
    }
}
