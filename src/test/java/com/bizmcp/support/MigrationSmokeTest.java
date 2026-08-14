package com.bizmcp.support;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class MigrationSmokeTest extends AbstractPostgresTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    void migrationsAndTestSeedApply() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchants", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isEqualTo(10);
        Integer revenue = jdbc.queryForObject("""
                SELECT SUM(total_amount)::int FROM orders
                WHERE merchant_id = 7 AND status = 'COMPLETED'
                  AND created_at BETWEEN '2026-01-01' AND '2026-01-31 23:59:59+08'
                """, Integer.class);
        assertThat(revenue).isEqualTo(950); // 450 + 420 + 80
    }
}
