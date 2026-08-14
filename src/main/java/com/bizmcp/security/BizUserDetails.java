package com.bizmcp.security;

import com.bizmcp.governance.BizPrincipal;
import com.bizmcp.governance.Role;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/** A user from {@code app_users}, carrying the role and tenant that scope every call. */
public class BizUserDetails implements UserDetails {

    private final long userId;
    private final String username;
    private final String password;
    private final Role role;
    private final long tenantId;
    private final String displayName;

    public BizUserDetails(long userId, String username, String password,
                          Role role, long tenantId, String displayName) {
        this.userId = userId;
        this.username = username;
        this.password = password;
        this.role = role;
        this.tenantId = tenantId;
        this.displayName = displayName;
    }

    public BizPrincipal toPrincipal(String clientName) {
        return new BizPrincipal(userId, username, role, tenantId, clientName);
    }

    public long userId() {
        return userId;
    }

    public Role role() {
        return role;
    }

    public long tenantId() {
        return tenantId;
    }

    public String displayName() {
        return displayName;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role.authority()));
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }
}
