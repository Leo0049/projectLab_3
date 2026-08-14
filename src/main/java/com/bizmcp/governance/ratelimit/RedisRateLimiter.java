package com.bizmcp.governance.ratelimit;

import com.bizmcp.governance.BizPrincipal;
import com.bizmcp.governance.GovernanceExceptions.RateLimitExceededException;
import com.bizmcp.governance.GovernanceProperties;
import com.bizmcp.governance.RiskTier;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Redis-backed quota counters, shared across application instances.
 *
 * <p>Fixed windows (INCR + EXPIRE) rather than a rolling token bucket: the
 * spec frames the limits as "60 per minute, 2,000 per day", which is a quota,
 * and a fixed window yields an exact "retry in N seconds" for the error
 * message in spec section 10. The trade-off is the usual one — up to 2x the
 * nominal rate across a window boundary. See README "Deviations from the spec".
 */
public class RedisRateLimiter implements RateLimiter {

    private final StringRedisTemplate redis;
    private final GovernanceProperties properties;
    private final Clock clock;

    public RedisRateLimiter(StringRedisTemplate redis, GovernanceProperties properties, Clock clock) {
        this.redis = redis;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void consumeOrThrow(BizPrincipal principal, RiskTier tier) {
        GovernanceProperties.Quota quota = properties.quotaFor(tier);
        Instant now = clock.instant();

        long secondsIntoMinute = now.getEpochSecond() % 60;
        long secondsIntoDay = now.getEpochSecond() % 86_400;

        long minuteCount = increment(
                key(principal, tier, "m", now.getEpochSecond() / 60),
                Duration.ofSeconds(60 - secondsIntoMinute));
        if (minuteCount > quota.getPerMinute()) {
            throw new RateLimitExceededException("分鐘", 60 - secondsIntoMinute);
        }

        long dayCount = increment(
                key(principal, tier, "d", now.getEpochSecond() / 86_400),
                Duration.ofSeconds(86_400 - secondsIntoDay));
        if (dayCount > quota.getPerDay()) {
            throw new RateLimitExceededException("每日", 86_400 - secondsIntoDay);
        }
    }

    private long increment(String key, Duration ttl) {
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, ttl);
        }
        return count == null ? 0L : count;
    }

    private String key(BizPrincipal principal, RiskTier tier, String window, long bucket) {
        // Tenant is part of every key. A quota key that omitted it would let one
        // tenant's traffic throttle another's.
        return "rl:%d:%d:%s:%s:%d".formatted(
                principal.tenantId(), principal.userId(), tier, window, bucket);
    }
}
