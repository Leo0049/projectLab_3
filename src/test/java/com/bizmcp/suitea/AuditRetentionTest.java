package com.bizmcp.suitea;

import com.bizmcp.audit.AuditRetentionService;
import com.bizmcp.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retention and archival (spec section 7.4).
 *
 * <p>The spec left this as policy only. It is the part an enterprise reviewer
 * actually asks about — how long is it kept, who can read it, how is it
 * deleted — so these tests exercise the real behaviour rather than a document.
 */
@SpringBootTest
class AuditRetentionTest extends AbstractPostgresTest {

    @Autowired AuditRetentionService retentionService;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clearRetentionState() {
        jdbc.update("DELETE FROM mcp_approval_request WHERE id LIKE 'apr_ret%'");
        jdbc.update("DELETE FROM mcp_audit_log WHERE trace_id LIKE 'trc_ret%'");
        for (String partition : archivePartitions()) {
            jdbc.execute("DROP TABLE IF EXISTS " + partition);
        }
    }

    private long insertAuditRow(String traceId, String ageInterval) {
        return jdbc.queryForObject("""
                INSERT INTO mcp_audit_log (trace_id, principal_id, principal_role, tenant_id,
                                           tool_name, risk_tier, arguments, decision, outcome,
                                           created_at)
                VALUES (?, 42, 'ADMIN', 7, 'query_sales_summary', 'T1', '{}'::jsonb,
                        'ALLOW', 'SUCCESS', now() - ?::interval)
                RETURNING id
                """, Long.class, traceId, ageInterval);
    }

    private List<String> archivePartitions() {
        return jdbc.queryForList("""
                SELECT child.relname
                FROM pg_inherits
                JOIN pg_class parent ON parent.oid = pg_inherits.inhparent
                JOIN pg_class child  ON child.oid  = pg_inherits.inhrelid
                WHERE parent.relname = 'mcp_audit_log_archive'
                """, String.class);
    }

    private int countIn(String table, long id) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE id = ?", Integer.class, id);
        return count == null ? 0 : count;
    }

    @Test
    void recordsInsideTheHotWindowStayInTheMainTable() {
        long recent = insertAuditRow("trc_ret_recent", "10 days");

        retentionService.archiveCooledAuditRecords();

        assertThat(countIn("mcp_audit_log", recent)).isEqualTo(1);
        assertThat(countIn("mcp_audit_log_archive", recent)).isZero();
    }

    @Test
    void recordsPastTheHotWindowMoveToTheArchive() {
        long old = insertAuditRow("trc_ret_old", "200 days");

        int archived = retentionService.archiveCooledAuditRecords();

        assertThat(archived).isGreaterThanOrEqualTo(1);
        // Exactly one of the two tables holds it: never both, never neither.
        assertThat(countIn("mcp_audit_log", old)).isZero();
        assertThat(countIn("mcp_audit_log_archive", old)).isEqualTo(1);
    }

    @Test
    void archivedRecordsKeepTheirContent() {
        long old = insertAuditRow("trc_ret_content", "200 days");
        retentionService.archiveCooledAuditRecords();

        var row = jdbc.queryForMap(
                "SELECT trace_id, tool_name, decision, tenant_id FROM mcp_audit_log_archive WHERE id = ?",
                old);

        // Archiving is a move, not a summary: evidence keeps its detail.
        assertThat(row.get("trace_id")).isEqualTo("trc_ret_content");
        assertThat(row.get("tool_name")).isEqualTo("query_sales_summary");
        assertThat(row.get("decision")).isEqualTo("ALLOW");
        assertThat(row.get("tenant_id")).isEqualTo(7L);
    }

    @Test
    void theArchiveIsPartitionedByMonth() {
        insertAuditRow("trc_ret_p1", "200 days");
        insertAuditRow("trc_ret_p2", "400 days");

        retentionService.archiveCooledAuditRecords();

        // Two records from different months land in different partitions, which
        // is what makes expiry a DROP rather than a bulk DELETE.
        assertThat(archivePartitions())
                .hasSizeGreaterThanOrEqualTo(2)
                .allSatisfy(name -> assertThat(name).startsWith("mcp_audit_log_archive_"));
    }

    @Test
    void partitionsPastTheArchiveWindowAreDropped() {
        insertAuditRow("trc_ret_ancient", "500 days");
        retentionService.archiveCooledAuditRecords();
        assertThat(archivePartitions()).isNotEmpty();

        int dropped = retentionService.dropExpiredArchivePartitions();

        assertThat(dropped).isGreaterThanOrEqualTo(1);
        assertThat(archivePartitions()).isEmpty();
    }

    @Test
    void anAuditRecordStillReferencedByAnApprovalIsNotArchived() {
        long referenced = insertAuditRow("trc_ret_referenced", "200 days");
        jdbc.update("""
                INSERT INTO mcp_approval_request (id, audit_log_id, tenant_id, tool_name,
                                                  arguments, requested_by, status, expires_at,
                                                  created_at)
                VALUES ('apr_ret_live', ?, 7, 'adjust_inventory', '{}'::jsonb, 42, 'PENDING',
                        now() + interval '1 day', now())
                """, referenced);

        retentionService.archiveCooledAuditRecords();

        // The foreign key would block the move, and an approver looking at a
        // live request should still be able to reach its audit trail.
        assertThat(countIn("mcp_audit_log", referenced)).isEqualTo(1);
    }

    @Test
    void approvalRequestsPastTheirWindowAreDeletedOutright() {
        long auditId = insertAuditRow("trc_ret_forapproval", "200 days");
        jdbc.update("""
                INSERT INTO mcp_approval_request (id, audit_log_id, tenant_id, tool_name,
                                                  arguments, requested_by, status, expires_at,
                                                  created_at)
                VALUES ('apr_ret_old', ?, 7, 'adjust_inventory',
                        '{"productId":1001,"delta":-50}'::jsonb, 42, 'EXECUTED',
                        now() - interval '199 days', now() - interval '200 days')
                """, auditId);

        int deleted = retentionService.purgeExpiredApprovals();

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        // Deleted, not archived: this table holds unmasked personal data.
        Integer remaining = jdbc.queryForObject(
                "SELECT count(*) FROM mcp_approval_request WHERE id = 'apr_ret_old'", Integer.class);
        assertThat(remaining).isZero();
    }

    @Test
    void aFullRunAppliesAllThreeStepsInOrder() {
        long ancient = insertAuditRow("trc_ret_full", "500 days");
        jdbc.update("""
                INSERT INTO mcp_approval_request (id, audit_log_id, tenant_id, tool_name,
                                                  arguments, requested_by, status, expires_at,
                                                  created_at)
                VALUES ('apr_ret_full', ?, 7, 'adjust_inventory', '{}'::jsonb, 42, 'EXECUTED',
                        now() - interval '499 days', now() - interval '500 days')
                """, ancient);

        AuditRetentionService.RetentionOutcome outcome = retentionService.apply();

        // Approvals are purged first, which is what frees the audit record to
        // be archived in the same run.
        assertThat(outcome.approvalsDeleted()).isGreaterThanOrEqualTo(1);
        assertThat(outcome.auditRecordsArchived()).isGreaterThanOrEqualTo(1);
        assertThat(outcome.partitionsDropped()).isGreaterThanOrEqualTo(1);
        assertThat(countIn("mcp_audit_log", ancient)).isZero();
    }

    @Test
    void aRunWithNothingToDoIsHarmless() {
        AuditRetentionService.RetentionOutcome outcome = retentionService.apply();

        assertThat(outcome.approvalsDeleted()).isZero();
        assertThat(outcome.auditRecordsArchived()).isZero();
        assertThat(outcome.partitionsDropped()).isZero();
    }
}
