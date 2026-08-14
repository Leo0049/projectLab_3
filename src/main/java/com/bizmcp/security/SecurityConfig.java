package com.bizmcp.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Filter chains (spec section 5.2).
 *
 * <p>Three separate chains, because they protect genuinely different things:
 * <ol>
 *   <li>{@code /mcp/**} — the MCP endpoint, an OAuth2 resource server;</li>
 *   <li>{@code /approvals/**} — the approval console, form login. v1.0 of the
 *       spec had no authentication here at all: a page that can approve stock
 *       changes, open on port 8080, is more dangerous than the MCP endpoint;</li>
 *   <li>everything else — login page and actuator.</li>
 * </ol>
 *
 * <p>The HTTP layer only establishes <em>who</em> is calling. It cannot see the
 * tool name or arguments, so it cannot do tool-level RBAC, masking or approval;
 * that is {@code GovernanceAspect}'s job (spec section 5.3).
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableConfigurationProperties(ApiKeyProperties.class)
public class SecurityConfig {

    @Bean
    @org.springframework.core.annotation.Order(2)
    public SecurityFilterChain mcpChain(HttpSecurity http,
                                        ApiKeyProperties apiKeyProperties,
                                        BizUserDetailsService userDetailsService) throws Exception {
        http.securityMatcher("/mcp/**", "/sse/**")
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                // MCP is a token-authenticated API, not a browser form post.
                .csrf(CsrfConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        if (apiKeyProperties.isEnabled()) {
            http.addFilterBefore(
                    new ApiKeyAuthenticationFilter(apiKeyProperties, userDetailsService),
                    UsernamePasswordAuthenticationFilter.class);
        }
        return http.build();
    }

    @Bean
    @org.springframework.core.annotation.Order(3)
    public SecurityFilterChain approvalChain(HttpSecurity http) throws Exception {
        return http.securityMatcher("/approvals/**")
                .authorizeHttpRequests(requests -> requests
                        .anyRequest().hasAnyRole("APPROVER", "ADMIN"))
                .formLogin(form -> form.loginPage("/login").permitAll())
                .logout(logout -> logout.logoutUrl("/logout").logoutSuccessUrl("/login"))
                .build();
    }

    @Bean
    @org.springframework.core.annotation.Order(4)
    public SecurityFilterChain defaultChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/login", "/error", "/css/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/**").hasAnyRole("ADMIN", "AUDITOR")
                        .anyRequest().authenticated())
                .formLogin(form -> form.loginPage("/login").permitAll())
                .logout(Customizer.withDefaults())
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // Delegating encoder: seeded hashes carry the {bcrypt} prefix.
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
