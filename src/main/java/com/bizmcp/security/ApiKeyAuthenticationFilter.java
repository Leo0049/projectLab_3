package com.bizmcp.security;

import com.bizmcp.governance.BizAuthenticationToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Authenticates the local-testing API key into the same {@link BizAuthenticationToken}
 * the OAuth path produces, so the governance chain sees one principal type.
 *
 * <p>An unrecognised key is left unauthenticated rather than rejected here,
 * letting the resource-server filters produce the standard 401.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);

    private final ApiKeyProperties properties;
    private final BizUserDetailsService userDetailsService;

    public ApiKeyAuthenticationFilter(ApiKeyProperties properties,
                                      BizUserDetailsService userDetailsService) {
        this.properties = properties;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String apiKey = request.getHeader(properties.getHeader());
        if (apiKey != null && !apiKey.isBlank()
            && SecurityContextHolder.getContext().getAuthentication() == null) {

            String username = properties.getKeys().get(apiKey);
            if (username != null) {
                try {
                    BizUserDetails user = userDetailsService.loadUserByUsername(username);
                    SecurityContextHolder.getContext().setAuthentication(
                            new BizAuthenticationToken(user.toPrincipal("mcp-inspector")));
                } catch (UsernameNotFoundException e) {
                    log.warn("api key maps to unknown user '{}'", username);
                }
            } else {
                log.debug("unrecognised api key presented");
            }
        }
        filterChain.doFilter(request, response);
    }
}
