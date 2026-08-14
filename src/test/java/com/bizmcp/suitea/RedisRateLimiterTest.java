package com.bizmcp.suitea;

import com.bizmcp.governance.BizPrincipal;
import com.bizmcp.governance.GovernanceExceptions.RateLimitExceededException;
import com.bizmcp.governance.GovernanceProperties;
import com.bizmcp.governance.RiskTier;
import com.bizmcp.governance.Role;
import com.bizmcp.governance.ratelimit.RedisRateLimiter;
import com.bizmcp.support.RedisTestServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link RedisRateLimiter} against a real Redis server.
 *
 * <p>The audit found that this class had never been constructed in any
 * environment, so nothing had ever exercised its Redis interaction. Wiring is
 * covered by {@code RateLimiterWiringTest}; this covers the behaviour.
 *
 * <p>The test that matters most is
 * {@link #countersAreSharedBetweenInstances()}: sharing counters across
 * instances is the entire reason this implementation exists, and it is exactly
 * what the in-memory fallback silently failed to do.
 */
class RedisRateLimiterTest {

    private static RedisTestServer redis;
    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate template;

    private final Clock clock = Clock.fixed(Instant.parse("2026-03-01T10:30:00Z"), ZoneOffset.UTC);

    @BeforeAll
    static void startRedis() {
        redis = RedisTestServer.startOrSkip();
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", redis.port()));
        connectionFactory.afterPropertiesSet();
        template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();
    }

    @AfterAll
    static void stopRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        if (redis != null) {
            redis.close();
        }
    }

    @BeforeEach
    void clearCounters() {
        Set<String> keys = template.keys("rl:*");
        if (keys != null && !keys.isEmpty()) {
            template.delete(keys);
        }
    }

    private GovernanceProperties properties(int perMinute, int perDay) {
        GovernanceProperties properties = new GovernanceProperties();
        properties.setRateLimits(new EnumMap<>(RiskTier.class));
        for (RiskTier tier : RiskTier.values()) {
            GovernanceProperties.Quota quota = new GovernanceProperties.Quota();
            quota.setPerMinute(perMinute);
            quota.setPerDay(perDay);
            properties.getRateLimits().put(tier, quota);
        }
        return properties;
    }

    private RedisRateLimiter limiter(int perMinute, int perDay) {
        return new RedisRateLimiter(template, properties(perMinute, perDay), clock);
    }

    private BizPrincipal principal(long userId, long tenantId) {
        return BizPrincipal.of(userId, "user-" + userId, Role.STORE_MANAGER, tenantId);
    }

    @Test
    void theMinuteQuotaIsEnforced() {
        RedisRateLimiter limiter = limiter(3, 1000);
        BizPrincipal caller = principal(1L, 7L);

        for (int i = 0; i < 3; i++) {
            limiter.consumeOrThrow(caller, RiskTier.T1);
        }

        assertThatThrownBy(() -> limiter.consumeOrThrow(caller, RiskTier.T1))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("分鐘");
    }

    @Test
    void theDayQuotaIsEnforcedIndependentlyOfTheMinuteQuota() {
        RedisRateLimiter limiter = limiter(1000, 2);
        BizPrincipal caller = principal(2L, 7L);

        limiter.consumeOrThrow(caller, RiskTier.T1);
        limiter.consumeOrThrow(caller, RiskTier.T1);

        assertThatThrownBy(() -> limiter.consumeOrThrow(caller, RiskTier.T1))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("每日");
    }

    @Test
    void countersAreSharedBetweenInstances() {
        // Two limiters standing in for two application instances behind a load
        // balancer. This is the property the in-memory implementation cannot
        // provide, and the reason the wiring bug mattered.
        RedisRateLimiter instanceA = limiter(4, 1000);
        RedisRateLimiter instanceB = limiter(4, 1000);
        BizPrincipal caller = principal(3L, 7L);

        instanceA.consumeOrThrow(caller, RiskTier.T1);
        instanceA.consumeOrThrow(caller, RiskTier.T1);
        instanceB.consumeOrThrow(caller, RiskTier.T1);
        instanceB.consumeOrThrow(caller, RiskTier.T1);

        // The fifth call is refused whichever instance receives it.
        assertThatThrownBy(() -> instanceB.consumeOrThrow(caller, RiskTier.T1))
                .isInstanceOf(RateLimitExceededException.class);
        assertThatThrownBy(() -> instanceA.consumeOrThrow(caller, RiskTier.T1))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void quotasAreScopedPerUser() {
        RedisRateLimiter limiter = limiter(1, 1000);

        limiter.consumeOrThrow(principal(10L, 7L), RiskTier.T1);
        assertThatThrownBy(() -> limiter.consumeOrThrow(principal(10L, 7L), RiskTier.T1))
                .isInstanceOf(RateLimitExceededException.class);

        assertThatCode(() -> limiter.consumeOrThrow(principal(11L, 7L), RiskTier.T1))
                .doesNotThrowAnyException();
    }

    @Test
    void quotasAreScopedPerTenant() {
        RedisRateLimiter limiter = limiter(1, 1000);

        limiter.consumeOrThrow(principal(20L, 7L), RiskTier.T1);

        // A key that omitted the tenant would let one merchant's traffic
        // throttle another's.
        assertThatCode(() -> limiter.consumeOrThrow(principal(20L, 9L), RiskTier.T1))
                .doesNotThrowAnyException();
    }

    @Test
    void quotasAreScopedPerTier() {
        RedisRateLimiter limiter = limiter(1, 1000);
        BizPrincipal caller = principal(30L, 7L);

        limiter.consumeOrThrow(caller, RiskTier.T1);

        // Exhausting reads must not block a write, and vice versa.
        assertThatCode(() -> limiter.consumeOrThrow(caller, RiskTier.T3))
                .doesNotThrowAnyException();
    }

    @Test
    void counterKeysExpireSoTheyDoNotAccumulateForever() {
        RedisRateLimiter limiter = limiter(10, 1000);
        limiter.consumeOrThrow(principal(40L, 7L), RiskTier.T1);

        Set<String> keys = template.keys("rl:7:40:*");
        assertThat(keys).isNotEmpty();

        for (String key : keys) {
            Long ttl = template.getExpire(key);
            assertThat(ttl)
                    .as("key %s must expire", key)
                    .isNotNull()
                    .isGreaterThan(0L);
        }
    }

    @Test
    void theMinuteKeyExpiresAtTheEndOfItsWindow() {
        RedisRateLimiter limiter = limiter(10, 1000);
        limiter.consumeOrThrow(principal(50L, 7L), RiskTier.T1);

        String minuteKey = template.keys("rl:7:50:T1:m:*").iterator().next();
        Long ttl = template.getExpire(minuteKey);

        // The clock is fixed at 10:30:00, so the window has a full 60s left.
        assertThat(ttl).isBetween(1L, 60L);
    }

    @Test
    void theRetryDelayReflectsTheWindowThatWasExhausted() {
        RedisRateLimiter limiter = limiter(1, 1000);
        BizPrincipal caller = principal(60L, 7L);
        limiter.consumeOrThrow(caller, RiskTier.T1);

        assertThatThrownBy(() -> limiter.consumeOrThrow(caller, RiskTier.T1))
                .isInstanceOfSatisfying(RateLimitExceededException.class, e -> {
                    assertThat(e.window()).isEqualTo("分鐘");
                    assertThat(e.retryAfterSeconds()).isBetween(1L, 60L);
                });
    }
}
