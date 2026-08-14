package com.bizmcp.suitea;

import com.bizmcp.config.BizMcpConfig;
import com.bizmcp.governance.GovernanceProperties;
import com.bizmcp.governance.RiskTier;
import com.bizmcp.governance.ratelimit.InMemoryRateLimiter;
import com.bizmcp.governance.ratelimit.RateLimiter;
import com.bizmcp.governance.ratelimit.RedisRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Which quota store gets wired, and what happens when it is misconfigured.
 *
 * <p>This exists because of a bug the audit found. The two limiter beans were
 * selected with {@code @ConditionalOnMissingBean} in user configuration, which
 * is evaluated <em>before</em> auto-configuration registers Redis. The
 * in-memory limiter therefore won unconditionally — in production too, where
 * {@code RedisRateLimiter} was never constructed and a multi-instance
 * deployment would have allowed N times the configured rate.
 *
 * <p>Nothing about that was observable from outside: no error, no log line, and
 * every quota test still passed. Asserting the wiring directly is the only
 * defence against it coming back.
 */
class RateLimiterWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(BizMcpConfig.class)
            .withPropertyValues(
                    "bizmcp.governance.rate-limits.T1.perMinute=60",
                    "bizmcp.governance.rate-limits.T1.perDay=2000");

    @Test
    void theMemoryBackendIsUsedWhenRequested() {
        contextRunner.withPropertyValues("bizmcp.governance.rate-limit-backend=MEMORY")
                .run(context -> assertThat(context.getBean(RateLimiter.class))
                        .isInstanceOf(InMemoryRateLimiter.class));
    }

    @Test
    void theRedisBackendIsUsedWhenRedisIsAvailable() {
        contextRunner
                .withPropertyValues("bizmcp.governance.rate-limit-backend=REDIS")
                .withUserConfiguration(RedisTemplateConfig.class)
                .run(context -> assertThat(context.getBean(RateLimiter.class))
                        .isInstanceOf(RedisRateLimiter.class));
    }

    @Test
    void askingForRedisWithoutRedisFailsFastInsteadOfDegrading() {
        contextRunner.withPropertyValues("bizmcp.governance.rate-limit-backend=REDIS")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("no StringRedisTemplate is available");
                });
    }

    @Test
    void theDefaultBackendIsRedis() {
        // A deployment has to opt in to weaker guarantees rather than fall into them.
        assertThat(new GovernanceProperties().getRateLimitBackend())
                .isEqualTo(GovernanceProperties.RateLimitBackend.REDIS);
    }

    @Test
    void aTierWithNoConfiguredQuotaFailsRatherThanRunningUnlimited() {
        assertThatThrownBy(() -> new GovernanceProperties().quotaFor(RiskTier.T3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("T3");
    }

    @Configuration(proxyBeanMethods = false)
    static class RedisTemplateConfig {
        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return mock(StringRedisTemplate.class);
        }
    }
}
