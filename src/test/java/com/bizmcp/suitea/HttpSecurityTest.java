package com.bizmcp.suitea;

import com.bizmcp.security.BizUserDetails;
import com.bizmcp.security.BizUserDetailsService;
import com.bizmcp.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP layer (spec section 5.2).
 *
 * <p>Everything else in Suite A starts from an already-authenticated
 * principal, which assumes the filter chains do their job. These tests check
 * that assumption.
 *
 * <p>The approval console gets particular attention: spec v1.0 had no
 * authentication on it at all, and a page that can approve stock changes is
 * more dangerous than the MCP endpoint itself.
 */
@SpringBootTest
class HttpSecurityTest extends AbstractPostgresTest {

    private static final String JSON_RPC_PING =
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";

    @Autowired WebApplicationContext context;
    @Autowired BizUserDetailsService userDetailsService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // --- MCP endpoint ---------------------------------------------------

    @Test
    void theMcpEndpointRejectsAnonymousCallers() throws Exception {
        mvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON_RPC_PING))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theMcpEndpointRejectsAnUnknownApiKey() throws Exception {
        mvc.perform(post("/mcp")
                        .header("X-API-Key", "not-a-real-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON_RPC_PING))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theMcpEndpointRejectsAGarbageBearerToken() throws Exception {
        mvc.perform(post("/mcp")
                        .header("Authorization", "Bearer not.a.jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON_RPC_PING))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aValidApiKeyGetsPastAuthentication() throws Exception {
        int status = mvc.perform(post("/mcp")
                        .header("X-API-Key", "demo-cs-lead-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .content(JSON_RPC_PING))
                .andReturn().getResponse().getStatus();

        // The protocol handshake may still object to a bare request; what this
        // asserts is that authentication and authorization are not the reason.
        assertThat(status).isNotIn(401, 403);
    }

    // --- approval console -----------------------------------------------

    @Test
    void theApprovalConsoleSendsAnonymousUsersToLogin() throws Exception {
        mvc.perform(get("/approvals"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void theApprovalConsoleRefusesAStoreManager() throws Exception {
        BizUserDetails alice = userDetailsService.loadUserByUsername("alice");
        assertThat(alice.role().name()).isEqualTo("STORE_MANAGER");

        // Being able to request a stock change must not imply being able to
        // approve it.
        mvc.perform(get("/approvals").with(user(alice)))
                .andExpect(status().isForbidden());
    }

    @Test
    void theApprovalConsoleRefusesACustomerServiceLead() throws Exception {
        BizUserDetails bob = userDetailsService.loadUserByUsername("bob");
        mvc.perform(get("/approvals").with(user(bob)))
                .andExpect(status().isForbidden());
    }

    @Test
    void theApprovalConsoleAdmitsAnApprover() throws Exception {
        BizUserDetails erin = userDetailsService.loadUserByUsername("erin");
        mvc.perform(get("/approvals").with(user(erin)))
                .andExpect(status().isOk());
    }

    @Test
    void theApprovalConsoleAdmitsAnAdmin() throws Exception {
        BizUserDetails carol = userDetailsService.loadUserByUsername("carol");
        mvc.perform(get("/approvals").with(user(carol)))
                .andExpect(status().isOk());
    }

    @Test
    void approvalActionsAreNotReachableAnonymously() throws Exception {
        int status = mvc.perform(post("/approvals/apr_whatever/approve"))
                .andReturn().getResponse().getStatus();

        // Blocked by CSRF before authorization even runs; either way it is refused.
        assertThat(status).isIn(401, 403);
    }

    @Test
    void approvalActionsRequireACsrfToken() throws Exception {
        BizUserDetails erin = userDetailsService.loadUserByUsername("erin");

        // A one-click approval endpoint is exactly what CSRF exists to protect:
        // without this, a page the approver visits could approve a stock change
        // on their behalf.
        mvc.perform(post("/approvals/apr_whatever/approve").with(user(erin)))
                .andExpect(status().isForbidden());

        int withToken = mvc.perform(post("/approvals/apr_whatever/approve")
                        .with(user(erin))
                        .with(csrf()))
                .andReturn().getResponse().getStatus();
        assertThat(withToken).isNotEqualTo(403);
    }

    // --- everything else -------------------------------------------------

    @Test
    void healthIsPublicButMetricsAreNot() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());

        int metricsStatus = mvc.perform(get("/actuator/metrics"))
                .andReturn().getResponse().getStatus();
        assertThat(metricsStatus).isNotEqualTo(200);
    }

    @Test
    void theLoginPageIsReachable() throws Exception {
        mvc.perform(get("/login")).andExpect(status().isOk());
    }
}
