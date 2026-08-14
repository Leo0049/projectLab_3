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

    @Test
    void everyProductHasStock() {
        // A product with no inventory row is invisible to check_inventory and
        // cannot be adjusted. Three tenant-9 products shipped that way once,
        // and only a live demo caught it.
        Integer orphans = jdbc.queryForObject("""
                SELECT count(*) FROM products p
                WHERE NOT EXISTS (SELECT 1 FROM inventory i WHERE i.product_id = p.product_id)
                """, Integer.class);
        assertThat(orphans).as("products without an inventory row").isZero();
    }

    @Test
    void inventoryAlwaysAgreesWithItsProductOnTenant() {
        Integer mismatched = jdbc.queryForObject("""
                SELECT count(*) FROM inventory i
                JOIN products p ON p.product_id = i.product_id
                WHERE p.merchant_id <> i.merchant_id
                """, Integer.class);
        // A row whose two tenant columns disagree would leak across the boundary
        // depending on which one a query filtered by.
        assertThat(mismatched).isZero();
    }

    @Test
    void bothTenantsHaveReferenceData() {
        for (long merchantId : new long[]{7L, 9L}) {
            for (String table : new String[]{"stores", "products", "inventory", "customers"}) {
                Integer count = jdbc.queryForObject(
                        "SELECT count(*) FROM " + table + " WHERE merchant_id = ?",
                        Integer.class, merchantId);
                assertThat(count)
                        .as("%s rows for merchant %d", table, merchantId)
                        .isGreaterThan(0);
            }
        }
    }
}
