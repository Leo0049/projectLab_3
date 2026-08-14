package com.bizmcp.config;

import com.bizmcp.audit.RetentionProperties;
import com.bizmcp.governance.GovernanceProperties;
import com.bizmcp.governance.ratelimit.InMemoryRateLimiter;
import com.bizmcp.governance.ratelimit.RateLimiter;
import com.bizmcp.governance.ratelimit.RedisRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({GovernanceProperties.class, RetentionProperties.class})
@EnableScheduling
public class BizMcpConfig {

    private static final Logger log = LoggerFactory.getLogger(BizMcpConfig.class);

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Selects the quota store from configuration.
     *
     * <p><b>Why this is not two {@code @ConditionalOnMissingBean} beans.</b>
     * It was, and it was wrong. {@code @ConditionalOnMissingBean} is only
     * reliable inside auto-configuration: user configuration is parsed
     * <em>before</em> auto-configuration registers its beans, so a condition on
     * {@code StringRedisTemplate} was always true at evaluation time. The
     * in-memory limiter therefore won unconditionally — including in
     * production, where {@code RedisRateLimiter} was never constructed at all
     * and per-instance quotas silently replaced shared ones.
     *
     * <p>Nothing failed, no log line appeared, and the quota tests still
     * passed. Choosing explicitly, and failing fast on a misconfiguration,
     * removes the whole class of problem.
     */
    @Bean
    public RateLimiter rateLimiter(GovernanceProperties properties,
                                   ObjectProvider<StringRedisTemplate> redisTemplate,
                                   Clock clock) {
        return switch (properties.getRateLimitBackend()) {
            case REDIS -> {
                StringRedisTemplate template = redisTemplate.getIfAvailable();
                if (template == null) {
                    // Degrading to per-instance counters would multiply every
                    // quota by the instance count without saying so.
                    throw new IllegalStateException(
                            "bizmcp.governance.rate-limit-backend=REDIS but no StringRedisTemplate is "
                            + "available. Configure spring.data.redis.*, or set the backend to MEMORY "
                            + "to accept per-instance quotas.");
                }
                log.info("rate limiting: Redis-backed quotas (shared across instances)");
                yield new RedisRateLimiter(template, properties, clock);
            }
            case MEMORY -> {
                log.warn("rate limiting: in-memory quotas. Counters are per instance, "
                         + "so a multi-instance deployment would allow N times the configured rate.");
                yield new InMemoryRateLimiter(properties, clock);
            }
        };
    }
}
