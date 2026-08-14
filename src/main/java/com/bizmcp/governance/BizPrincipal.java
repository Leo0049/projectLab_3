package com.bizmcp.governance;

/**
 * The authenticated caller, as the governance chain sees it.
 *
 * <p>{@code tenantId} is the load-bearing field: every tenant-scoped query
 * takes its tenant from here and nowhere else. It is derived from the token,
 * so a model cannot influence it no matter what the user types (spec
 * section 3.2, step 9).
 */
public record BizPrincipal(
        long userId,
        String username,
        Role role,
        long tenantId,
        String clientName
) {
    public BizPrincipal {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("principal has no username");
        }
        if (role == null) {
            throw new IllegalArgumentException("principal has no role");
        }
    }

    public static BizPrincipal of(long userId, String username, Role role, long tenantId) {
        return new BizPrincipal(userId, username, role, tenantId, "unknown");
    }

    public BizPrincipal withClient(String client) {
        return new BizPrincipal(userId, username, role, tenantId, client);
    }
}
