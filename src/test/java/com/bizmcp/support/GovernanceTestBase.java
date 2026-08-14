package com.bizmcp.support;

import com.bizmcp.governance.BizAuthenticationToken;
import com.bizmcp.governance.BizPrincipal;
import com.bizmcp.governance.Role;
import com.bizmcp.governance.ratelimit.InMemoryRateLimiter;
import com.bizmcp.governance.ratelimit.RateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Base for Suite A. Boots the full application against embedded PostgreSQL and
 * provides identity switching, because almost every governance assertion is
 * "the same call, a different caller, a different answer".
 */
@SpringBootTest
@Import(McpToolInvoker.class)
public abstract class GovernanceTestBase extends AbstractPostgresTest {

    protected static final long TENANT_A = 7L;   // 珍豆坊
    protected static final long TENANT_B = 9L;   // 茶研所

    @Autowired protected McpToolInvoker tools;
    @Autowired protected RateLimiter rateLimiter;

    @BeforeEach
    void resetQuotas() {
        // Quota windows are shared process state; without this, later tests in a
        // class would fail for the wrong reason.
        if (rateLimiter instanceof InMemoryRateLimiter inMemory) {
            inMemory.reset();
        }
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** Runs subsequent calls as the given identity. */
    protected void actAs(Role role, long tenantId) {
        actAs(role, tenantId, 42L, role.name().toLowerCase());
    }

    protected void actAs(Role role, long tenantId, long userId, String username) {
        BizPrincipal principal = new BizPrincipal(userId, username, role, tenantId, "suite-a");
        SecurityContextHolder.getContext().setAuthentication(new BizAuthenticationToken(principal));
    }
}
