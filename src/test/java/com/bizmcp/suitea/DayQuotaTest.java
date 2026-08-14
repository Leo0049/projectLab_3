package com.bizmcp.suitea;

import com.bizmcp.audit.AuditLog;
import com.bizmcp.audit.AuditLogRepository;
import com.bizmcp.governance.Role;
import com.bizmcp.support.GovernanceTestBase;
import com.bizmcp.support.ToolArguments;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The daily quota (spec section 6.1).
 *
 * <p>The minute quota alone does not bound anything that matters: an agent
 * loop running at 60 calls a minute all day is 86,400 calls, which is a cost
 * problem and an exfiltration problem rather than a rate problem. The day
 * window is the one that actually caps exposure, and it had no coverage — the
 * real limits are too high to reach in a test, so this context lowers them.
 */
@SpringBootTest(properties = {
        "bizmcp.governance.rate-limits.T2.perMinute=60",
        "bizmcp.governance.rate-limits.T2.perDay=2"
})
class DayQuotaTest extends GovernanceTestBase {

    @Autowired AuditLogRepository auditLogRepository;

    @Test
    void theDayQuotaStopsCallsThatAreStillWithinTheMinuteQuota() {
        actAs(Role.CS_LEAD, TENANT_A);
        var arguments = ToolArguments.validFor("search_group_orders");

        assertThat(tools.isError(tools.call("search_group_orders", arguments))).isFalse();
        assertThat(tools.isError(tools.call("search_group_orders", arguments))).isFalse();

        // Third call is well inside the 60/minute allowance and still refused.
        assertThat(tools.isError(tools.call("search_group_orders", arguments))).isTrue();
    }

    @Test
    void theDayQuotaMessageNamesTheWindow() {
        actAs(Role.CS_LEAD, TENANT_A);
        var arguments = ToolArguments.validFor("search_group_orders");
        tools.call("search_group_orders", arguments);
        tools.call("search_group_orders", arguments);

        String message = tools.callForWirePayload("search_group_orders", arguments);

        // "retry in 45 seconds" would be a lie for a daily window.
        assertThat(message).contains("每日").contains("秒後重試");
    }

    @Test
    void exceedingTheDayQuotaIsAudited() {
        actAs(Role.CS_LEAD, TENANT_A);
        var arguments = ToolArguments.validFor("search_group_orders");
        for (int i = 0; i < 3; i++) {
            tools.call("search_group_orders", arguments);
        }

        AuditLog latest = auditLogRepository.findByToolNameOrderByIdDesc("search_group_orders").get(0);
        assertThat(latest.getDecision()).isEqualTo("DENY");
        assertThat(latest.getDenyReason()).isEqualTo("RATE_LIMITED");
    }

    @Test
    void quotasAreChargedPerIdentityNotGlobally() {
        var arguments = ToolArguments.validFor("search_group_orders");

        actAs(Role.CS_LEAD, TENANT_A, 43L, "bob");
        tools.call("search_group_orders", arguments);
        tools.call("search_group_orders", arguments);
        assertThat(tools.isError(tools.call("search_group_orders", arguments))).isTrue();

        // A different user must not inherit someone else's exhausted quota.
        actAs(Role.CS_LEAD, TENANT_A, 99L, "another-lead");
        assertThat(tools.isError(tools.call("search_group_orders", arguments))).isFalse();
    }
}
