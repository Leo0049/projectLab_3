package com.bizmcp.governance;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Turns the current {@code SecurityContext} into a {@link BizPrincipal}.
 *
 * <p>Both authentication paths converge here, so the governance chain never
 * needs to know whether the caller arrived over OAuth or the local API-key
 * shortcut — and, more importantly, neither path can supply a tenant that did
 * not come from a verified credential.
 */
@Component
public class PrincipalResolver {

    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_TENANT = "tenant_id";
    public static final String CLAIM_USER_ID = "user_id";

    public BizPrincipal current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException("no authenticated principal for this tool call");
        }
        if (authentication instanceof BizAuthenticationToken token) {
            return token.getPrincipal();
        }
        if (authentication instanceof JwtAuthenticationToken jwtToken) {
            return fromJwt(jwtToken.getToken());
        }
        if (authentication.getPrincipal() instanceof BizPrincipal principal) {
            return principal;
        }
        throw new IllegalStateException(
                "unsupported authentication type: " + authentication.getClass().getName());
    }

    public BizPrincipal fromJwt(Jwt jwt) {
        Role role = Role.fromClaim(jwt.getClaimAsString(CLAIM_ROLE));
        Long tenantId = claimAsLong(jwt, CLAIM_TENANT);
        Long userId = claimAsLong(jwt, CLAIM_USER_ID);
        if (tenantId == null) {
            // Never fall back to a default tenant: an unscoped token must not
            // become an accidental grant over someone else's data.
            throw new IllegalStateException("token carries no tenant_id claim");
        }
        String username = jwt.getSubject() == null ? "unknown" : jwt.getSubject();
        String client = jwt.getClaimAsString("azp");
        return new BizPrincipal(
                userId == null ? 0L : userId,
                username,
                role,
                tenantId,
                client == null ? "oauth-client" : client);
    }

    private Long claimAsLong(Jwt jwt, String claim) {
        Object value = jwt.getClaim(claim);
        return switch (value) {
            case null -> null;
            case Number number -> number.longValue();
            case String text -> text.isBlank() ? null : Long.parseLong(text.trim());
            default -> throw new IllegalStateException("claim " + claim + " is not numeric: " + value);
        };
    }
}
