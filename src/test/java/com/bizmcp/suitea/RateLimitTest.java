package com.bizmcp.suitea;

import com.bizmcp.audit.AuditLog;
import com.bizmcp.audit.AuditLogRepository;
import com.bizmcp.governance.Role;
import com.bizmcp.support.GovernanceTestBase;
import com.bizmcp.support.ToolArguments;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Per-tier quotas (spec section 6.1). T3 is capped at 5 calls a minute. */
class RateLimitTest extends GovernanceTestBase {

    @Autowired AuditLogRepository auditLogRepository;

    @Test
    void theQuotaStoreUnderTestIsTheInMemoryOne() {
        // Guards against the tests silently exercising a different
        // implementation than the one they claim to. See RateLimiterWiringTest.
        assertThat(rateLimiter)
                .isInstanceOf(com.bizmcp.governance.ratelimit.InMemoryRateLimiter.class);
    }

    @Test
    void theMinuteQuotaStopsTheSixthWriteAttempt() {
        actAs(Role.STORE_MANAGER, TENANT_A);
        Map<String, Object> arguments = ToolArguments.validFor("adjust_inventory");

        for (int i = 1; i <= 5; i++) {
            assertThat(tools.isError(tools.call("adjust_inventory", arguments)))
                    .as("call %d of the T3 minute quota", i)
                    .isFalse();
        }

        McpSchema.CallToolResult sixth = tools.call("adjust_inventory", arguments);
        assertThat(tools.isError(sixth)).isTrue();
    }

    @Test
    void theQuotaMessageTellsTheModelHowLongToWait() {
        actAs(Role.STORE_MANAGER, TENANT_A);
        Map<String, Object> arguments = ToolArguments.validFor("adjust_inventory");
        for (int i = 0; i < 5; i++) {
            tools.call("adjust_inventory", arguments);
        }

        String message = tools.callForWirePayload("adjust_inventory", arguments);

        // Actionable rather than just "429" (spec section 10).
        assertThat(message).contains("速率上限").contains("秒後重試");
    }

    @Test
    void quotaRejectionsAreAudited() {
        actAs(Role.STORE_MANAGER, TENANT_A);
        Map<String, Object> arguments = ToolArguments.validFor("adjust_inventory");
        for (int i = 0; i < 6; i++) {
            tools.call("adjust_inventory", arguments);
        }

        AuditLog latest = auditLogRepository.findByToolNameOrderByIdDesc("adjust_inventory").get(0);
        assertThat(latest.getDecision()).isEqualTo("DENY");
        assertThat(latest.getDenyReason()).isEqualTo("RATE_LIMITED");
    }

    @Test
    void readToolsHaveTheirOwnMoreGenerousQuota() {
        actAs(Role.ADMIN, TENANT_A);
        // T1 allows 60/minute, so a burst that exhausts T3 does not touch reads.
        for (int i = 0; i < 10; i++) {
            assertThat(tools.isError(tools.call("check_inventory", Map.of()))).isFalse();
        }
    }
}
