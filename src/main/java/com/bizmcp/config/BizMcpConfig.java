package com.bizmcp.config;

import com.bizmcp.governance.GovernanceProperties;
import com.bizmcp.governance.ratelimit.InMemoryRateLimiter;
import com.bizmcp.governance.ratelimit.RateLimiter;
import com.bizmcp.governance.ratelimit.RedisRateLimiter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GovernanceProperties.class)
@EnableScheduling
public class BizMcpConfig {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Redis-backed quotas when Redis is configured, in-memory otherwise.
     *
     * <p>The in-memory path is not a second implementation of the policy: both
     * share the same window semantics, and it exists so the governance tests
     * (and a laptop with no Redis) can exercise quota enforcement. Only the
     * Redis one coordinates across instances.
     */
    @Bean
    @ConditionalOnMissingBean(RateLimiter.class)
    public RateLimiter redisRateLimiter(StringRedisTemplate redisTemplate,
                                        GovernanceProperties properties,
                                        Clock clock) {
        return new RedisRateLimiter(redisTemplate, properties, clock);
    }

    @Configuration(proxyBeanMethods = false)
    static class NoRedisConfiguration {

        @Bean
        @ConditionalOnMissingBean({RateLimiter.class, StringRedisTemplate.class})
        public RateLimiter inMemoryRateLimiter(GovernanceProperties properties, Clock clock) {
            return new InMemoryRateLimiter(properties, clock);
        }
    }
}
