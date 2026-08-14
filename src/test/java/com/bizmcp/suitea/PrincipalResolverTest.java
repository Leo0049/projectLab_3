package com.bizmcp.suitea;

import com.bizmcp.governance.BizPrincipal;
import com.bizmcp.governance.PrincipalResolver;
import com.bizmcp.governance.Role;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Turning an access token into the identity everything else trusts
 * (spec section 5.1).
 *
 * <p>Suite A's integration tests authenticate through the API-key path, so the
 * OAuth path — the one that actually matters in production — had no coverage
 * at all. The important case is the last one: a token without a tenant claim
 * must be refused, never quietly defaulted to some tenant.
 */
class PrincipalResolverTest {

    private final PrincipalResolver resolver = new PrincipalResolver();

    private Jwt.Builder token() {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .subject("alice");
    }

    @Test
    void claimsBecomeThePrincipal() {
        Jwt jwt = token()
                .claim("role", "STORE_MANAGER")
                .claim("tenant_id", 7)
                .claim("user_id", 42)
                .claim("azp", "claude-desktop")
                .build();

        BizPrincipal principal = resolver.fromJwt(jwt);

        assertThat(principal.username()).isEqualTo("alice");
        assertThat(principal.role()).isEqualTo(Role.STORE_MANAGER);
        assertThat(principal.tenantId()).isEqualTo(7L);
        assertThat(principal.userId()).isEqualTo(42L);
        assertThat(principal.clientName()).isEqualTo("claude-desktop");
    }

    @Test
    void aTokenWithoutATenantClaimIsRefused() {
        Jwt jwt = token().claim("role", "ADMIN").claim("user_id", 1).build();

        // Defaulting here would turn a misconfigured token into access over
        // somebody's data. Refusing is the only safe behaviour.
        assertThatThrownBy(() -> resolver.fromJwt(jwt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tenant_id");
    }

    @Test
    void aTokenWithoutARoleClaimIsRefused() {
        Jwt jwt = token().claim("tenant_id", 7).build();

        assertThatThrownBy(() -> resolver.fromJwt(jwt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("role");
    }

    @Test
    void anUnknownRoleIsRefusedRatherThanIgnored() {
        Jwt jwt = token().claim("role", "SUPERUSER").claim("tenant_id", 7).build();

        assertThatThrownBy(() -> resolver.fromJwt(jwt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SUPERUSER");
    }

    @Test
    void roleClaimsAreNormalised() {
        for (String claim : new String[]{"cs_lead", "CS_LEAD", "ROLE_CS_LEAD", " cs_lead "}) {
            Jwt jwt = token().claim("role", claim).claim("tenant_id", 7).build();
            assertThat(resolver.fromJwt(jwt).role())
                    .as("role claim %s", claim)
                    .isEqualTo(Role.CS_LEAD);
        }
    }

    @Test
    void numericClaimsAreAcceptedAsStringsToo() {
        Jwt jwt = token()
                .claim("role", "ADMIN")
                .claim("tenant_id", "9")
                .claim("user_id", "48")
                .build();

        BizPrincipal principal = resolver.fromJwt(jwt);
        assertThat(principal.tenantId()).isEqualTo(9L);
        assertThat(principal.userId()).isEqualTo(48L);
    }

    @Test
    void aNonNumericTenantClaimIsRefused() {
        Jwt jwt = token().claim("role", "ADMIN").claim("tenant_id", true).build();

        assertThatThrownBy(() -> resolver.fromJwt(jwt))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void thereIsNoPrincipalWhenNobodyIsAuthenticated() {
        assertThatThrownBy(resolver::current)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no authenticated principal");
    }
}
