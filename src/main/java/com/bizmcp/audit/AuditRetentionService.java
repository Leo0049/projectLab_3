package com.bizmcp.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Applies the retention policy (spec section 7.4).
 *
 * <p>The spec left this as policy only, on the grounds that four weeks was not
 * enough to build it. The policy is the part an enterprise reviewer asks about
 * — "how long do you keep it, who can read it, how is it deleted" — so it is
 * implemented here rather than described.
 *
 * <p>Three steps, in this order:
 * <ol>
 *   <li>delete approval requests past their window, because they hold unmasked
 *       arguments and are the most sensitive rows in the system;</li>
 *   <li>move cooled audit records into the monthly-partitioned archive;</li>
 *   <li>drop archive partitions that have aged out.</li>
 * </ol>
 */
@Service
public class AuditRetentionService {

    private static final Logger log = LoggerFactory.getLogger(AuditRetentionService.class);
    private static final DateTimeFormatter PARTITION_SUFFIX = DateTimeFormatter.ofPattern("yyyyMM");
    private static final String ARCHIVE_TABLE = "mcp_audit_log_archive";

    private final NamedParameterJdbcTemplate jdbc;
    private final RetentionProperties properties;
    private final Clock clock;

    public AuditRetentionService(NamedParameterJdbcTemplate jdbc,
                                 RetentionProperties properties,
                                 Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    /** Runs the whole policy. Returns a summary for logging and tests. */
    public RetentionOutcome apply() {
        int approvals = purgeExpiredApprovals();
        int archived = archiveCooledAuditRecords();
        int partitions = dropExpiredArchivePartitions();
        return new RetentionOutcome(approvals, archived, partitions);
    }

    /**
     * Deletes approval requests past their window.
     *
     * <p>Runs first: an approval row pins the audit record it references, so
     * clearing these is what lets the audit rows cool.
     */
    @Transactional
    public int purgeExpiredApprovals() {
        OffsetDateTime cutoff = now().minus(properties.getApprovalWindow());
        int deleted = jdbc.update(
                "DELETE FROM mcp_approval_request WHERE created_at < :cutoff",
                new MapSqlParameterSource("cutoff", cutoff));
        if (deleted > 0) {
            log.info("retention: deleted {} approval request(s) older than {}",
                    deleted, properties.getApprovalWindow());
        }
        return deleted;
    }

    /**
     * Moves audit records out of the hot table once they are past the hot
     * window. The move is a single statement so a record is never in both
     * tables, and never in neither.
     */
    @Transactional
    public int archiveCooledAuditRecords() {
        OffsetDateTime cutoff = now().minus(properties.getAuditHotWindow());

        List<OffsetDateTime> months = jdbc.queryForList("""
                SELECT DISTINCT date_trunc('month', created_at) AS month
                FROM mcp_audit_log
                WHERE created_at < :cutoff
                """, new MapSqlParameterSource("cutoff", cutoff), OffsetDateTime.class);

        if (months.isEmpty()) {
            return 0;
        }
        months.forEach(month -> ensurePartitionExists(YearMonth.from(month)));

        // A record still referenced by a live approval request stays hot: the
        // foreign key would block the delete, and an approver looking at a
        // pending request should still be able to reach its audit trail.
        int archived = jdbc.update("""
                WITH cooled AS (
                    DELETE FROM mcp_audit_log a
                    WHERE a.created_at < :cutoff
                      AND NOT EXISTS (
                          SELECT 1 FROM mcp_approval_request r WHERE r.audit_log_id = a.id)
                    RETURNING *
                )
                INSERT INTO mcp_audit_log_archive (
                    id, trace_id, principal_id, principal_role, tenant_id, tool_name,
                    risk_tier, arguments, decision, outcome, deny_reason, row_count,
                    latency_ms, client_name, created_at)
                SELECT id, trace_id, principal_id, principal_role, tenant_id, tool_name,
                       risk_tier, arguments, decision, outcome, deny_reason, row_count,
                       latency_ms, client_name, created_at
                FROM cooled
                """, new MapSqlParameterSource("cutoff", cutoff));

        if (archived > 0) {
            log.info("retention: archived {} audit record(s) older than {}",
                    archived, properties.getAuditHotWindow());
        }
        return archived;
    }

    /**
     * Drops whole partitions rather than deleting rows. This is why the archive
     * is partitioned at all: expiry becomes a metadata operation instead of a
     * bulk delete that leaves the table bloated.
     *
     * @return the number of partitions dropped
     */
    @Transactional
    public int dropExpiredArchivePartitions() {
        YearMonth cutoffMonth = YearMonth.from(now().minus(properties.getAuditArchiveWindow()));

        List<String> partitions = jdbc.queryForList("""
                SELECT child.relname
                FROM pg_inherits
                JOIN pg_class parent ON parent.oid = pg_inherits.inhparent
                JOIN pg_class child  ON child.oid  = pg_inherits.inhrelid
                WHERE parent.relname = :parent
                ORDER BY child.relname
                """, new MapSqlParameterSource("parent", ARCHIVE_TABLE), String.class);

        int dropped = 0;
        for (String partition : partitions) {
            YearMonth month = monthOf(partition);
            if (month != null && month.isBefore(cutoffMonth)) {
                // Identifier is derived from our own naming scheme, never user input.
                jdbc.getJdbcTemplate().execute("DROP TABLE IF EXISTS " + partition);
                log.info("retention: dropped expired archive partition {}", partition);
                dropped++;
            }
        }
        return dropped;
    }

    private void ensurePartitionExists(YearMonth month) {
        String partition = partitionName(month);
        String from = month.atDay(1).toString();
        String to = month.plusMonths(1).atDay(1).toString();

        jdbc.getJdbcTemplate().execute(
                "CREATE TABLE IF NOT EXISTS %s PARTITION OF %s FOR VALUES FROM ('%s') TO ('%s')"
                        .formatted(partition, ARCHIVE_TABLE, from, to));
    }

    private static String partitionName(YearMonth month) {
        return ARCHIVE_TABLE + "_" + month.format(PARTITION_SUFFIX);
    }

    private static YearMonth monthOf(String partitionName) {
        String suffix = partitionName.substring(partitionName.lastIndexOf('_') + 1);
        if (suffix.length() != 6 || !suffix.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return YearMonth.parse(suffix, PARTITION_SUFFIX);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    public record RetentionOutcome(int approvalsDeleted, int auditRecordsArchived, int partitionsDropped) {
    }
}
