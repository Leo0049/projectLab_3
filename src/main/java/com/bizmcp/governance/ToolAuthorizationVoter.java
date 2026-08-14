package com.bizmcp.governance;

import org.springframework.stereotype.Component;

/**
 * Tool-level RBAC (spec section 5.3). Default-deny: a call is allowed only if
 * the caller's role appears in the tool's {@link ToolRisk#allow()} list.
 *
 * <p>This is intentionally the only place the decision is made, so the same
 * policy drives both call-time enforcement and {@code tools/list} filtering
 * (spec section 5.4) and the two can never drift apart.
 */
@Component
public class ToolAuthorizationVoter {

    public Decision vote(BizPrincipal principal, ToolCall call) {
        return vote(principal.role(), call.toolName(), call.allowedRoles());
    }

    public Decision vote(Role role, String toolName, Role[] allowedRoles) {
        if (allowedRoles == null || allowedRoles.length == 0) {
            return Decision.denyUnknownTool(toolName);
        }
        return isVisibleTo(role, allowedRoles)
                ? Decision.allow()
                : Decision.denyInsufficientRole(toolName, allowedRoles);
    }

    /**
     * Whether a role may use a tool at all.
     *
     * <p>Enforcement above is expressed in terms of this method rather than
     * repeating the check, so "may call" and "may see" can never drift apart —
     * which is the failure the spec's section 5.4 filtering would otherwise
     * invite.
     */
    public boolean isVisibleTo(Role role, Role[] allowedRoles) {
        if (allowedRoles == null) {
            return false;
        }
        for (Role allowed : allowedRoles) {
            if (allowed == role) {
                return true;
            }
        }
        return false;
    }
}
