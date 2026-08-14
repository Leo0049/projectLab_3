package com.bizmcp.security;

import com.bizmcp.governance.Role;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class BizUserDetailsService implements UserDetailsService {

    private final NamedParameterJdbcTemplate jdbc;

    public BizUserDetailsService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public BizUserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        try {
            return jdbc.queryForObject("""
                    SELECT user_id, username, password_hash, role, merchant_id, display_name
                    FROM app_users WHERE username = :username
                    """,
                    new MapSqlParameterSource("username", username),
                    (rs, rowNum) -> new BizUserDetails(
                            rs.getLong("user_id"),
                            rs.getString("username"),
                            rs.getString("password_hash"),
                            Role.fromClaim(rs.getString("role")),
                            rs.getLong("merchant_id"),
                            rs.getString("display_name")));
        } catch (EmptyResultDataAccessException e) {
            throw new UsernameNotFoundException("no such user: " + username);
        }
    }
}
