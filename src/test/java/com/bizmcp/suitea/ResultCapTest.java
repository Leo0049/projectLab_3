package com.bizmcp.suitea;

import com.bizmcp.audit.AuditLog;
import com.bizmcp.audit.AuditLogRepository;
import com.bizmcp.governance.Role;
import com.bizmcp.support.GovernanceTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The response size cap (spec section 9.4).
 *
 * <p>A tool result becomes context the model has to carry, so an unbounded
 * result set does not just slow things down — it can push the rest of the
 * conversation out of the window. The seeded dataset is far below the cap, so
 * this test creates enough rows to cross it; without it, the cap was never
 * exercised at all.
 */
class ResultCapTest extends GovernanceTestBase {

    private static final int CAP = 200;
    private static final String MARKER = "cap-test";

    @Autowired JdbcTemplate jdbc;
    @Autowired AuditLogRepository auditLogRepository;

    @BeforeEach
    void createMoreRowsThanTheCap() {
        jdbc.update("""
                INSERT INTO group_orders (merchant_id, store_id, title, status, created_at)
                SELECT 7, 101, ? || ' #' || n, 'OPEN', TIMESTAMPTZ '2026-01-15 10:00:00+08'
                FROM generate_series(1, 250) AS n
                """, MARKER);
    }

    @AfterEach
    void removeThem() {
        jdbc.update("DELETE FROM group_orders WHERE title LIKE ?", MARKER + "%");
    }

    @Test
    void aResultLargerThanTheCapIsTruncated() {
        actAs(Role.CS_LEAD, TENANT_A);
        String payload = tools.callForWirePayload("search_group_orders",
                Map.of("startDate", "2026-01-01", "endDate", "2026-01-31"));

        assertThat(payload).contains("\"truncated\":true");
    }

    @Test
    void truncationIsAnnouncedRatherThanSilent() {
        actAs(Role.CS_LEAD, TENANT_A);
        String payload = tools.callForWirePayload("search_group_orders",
                Map.of("startDate", "2026-01-01", "endDate", "2026-01-31"));

        // Silently dropping rows would let the model answer "there are 200"
        // when there are 254. The note tells it the answer is incomplete and
        // what to do about it.
        assertThat(payload).contains("上限").contains(String.valueOf(CAP));
        assertThat(payload).contains("縮小");
    }

    @Test
    void neverMoreThanTheCapIsReturned() {
        actAs(Role.CS_LEAD, TENANT_A);
        tools.call("search_group_orders",
                Map.of("startDate", "2026-01-01", "endDate", "2026-01-31"));

        AuditLog latest = auditLogRepository.findByToolNameOrderByIdDesc("search_group_orders").get(0);
        assertThat(latest.getRowCount()).isEqualTo(CAP);
    }

    @Test
    void aResultWithinTheCapIsNotMarkedTruncated() {
        actAs(Role.CS_LEAD, TENANT_A);
        // A narrow window that excludes the generated rows.
        String payload = tools.callForWirePayload("search_group_orders",
                Map.of("startDate", "2026-01-05", "endDate", "2026-01-06"));

        assertThat(payload).contains("\"truncated\":false");
    }
}
