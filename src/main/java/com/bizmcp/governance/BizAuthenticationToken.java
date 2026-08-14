package com.bizmcp.governance;

import org.springframework.security.authentication.AbstractAuthenticationToken;

import java.util.List;

/**
 * Authentication carrying a resolved {@link BizPrincipal}. Produced by the
 * API-key filter used for local testing; the OAuth path builds the same
 * principal from JWT claims.
 */
public class BizAuthenticationToken extends AbstractAuthenticationToken {

    private final transient BizPrincipal principal;

    public BizAuthenticationToken(BizPrincipal principal) {
        super(List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                principal.role().authority())));
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public BizPrincipal getPrincipal() {
        return principal;
    }

    @Override
    public String getName() {
        return principal.username();
    }
}
