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
        for (Role allowed : allowedRoles) {
            if (allowed == role) {
                return Decision.allow();
            }
        }
        return Decision.denyInsufficientRole(toolName, allowedRoles);
    }

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
