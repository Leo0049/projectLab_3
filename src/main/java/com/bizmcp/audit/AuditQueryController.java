package com.bizmcp.audit;

import com.bizmcp.governance.BizPrincipal;
import com.bizmcp.governance.TraceIds;
import com.bizmcp.security.BizUserDetails;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read access to the audit trail (spec section 7.4).
 *
 * <p>Three rules are enforced here, and each exists because of a specific way
 * an audit trail stops being trustworthy:
 * <ul>
 *   <li><b>AUDITOR or ADMIN only</b> — enforced in {@code SecurityConfig}.</li>
 *   <li><b>Reads are themselves audited.</b> Otherwise the role that can see
 *       everything is the only one that leaves no trace.</li>
 *   <li><b>Approval arguments are never returned.</b> They are stored unmasked
 *       so an approver can judge the request; exposing them through a general
 *       audit query would turn the audit surface into a way to read personal
 *       data that masking exists to prevent.</li>
 * </ul>
 *
 * <p>Results are tenant-scoped like everything else.
 */
@RestController
@RequestMapping("/audit")
public class AuditQueryController {

    private static final int MAX_ROWS = 200;

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditWriter auditWriter;

    public AuditQueryController(NamedParameterJdbcTemplate jdbc, AuditWriter auditWriter) {
        this.jdbc = jdbc;
        this.auditWriter = auditWriter;
    }

    public record AuditEntry(
            long id, String traceId, long principalId, String principalRole, String toolName,
            String riskTier, String arguments, String decision, String outcome,
            String denyReason, Integer rowCount, Integer latencyMs, String createdAt) {
    }

    /** Approval requests without their arguments or preview. */
    public record ApprovalSummary(
            String id, String toolName, String status, long requestedBy, Integer retryCount,
            String createdAt, String decidedAt, String executedAt) {
    }

    @GetMapping("/logs")
    public List<AuditEntry> logs(@AuthenticationPrincipal BizUserDetails user,
                                 @RequestParam(required = false) String toolName,
                                 @RequestParam(defaultValue = "50") int limit) {

        BizPrincipal principal = user.toPrincipal("audit-console");
        auditWriter.recordAuditAccess(principal, "audit_query_logs", TraceIds.next());

        return jdbc.query("""
                SELECT id, trace_id, principal_id, principal_role, tool_name, risk_tier,
                       arguments::text AS arguments, decision, outcome, deny_reason,
                       row_count, latency_ms, created_at
                FROM mcp_audit_log
                WHERE tenant_id = :tenantId
                  AND (:toolName::varchar IS NULL OR tool_name = :toolName::varchar)
                ORDER BY id DESC
                LIMIT :limit
                """,
                new MapSqlParameterSource()
                        .addValue("tenantId", principal.tenantId())
                        .addValue("toolName", (toolName == null || toolName.isBlank()) ? null : toolName)
                        .addValue("limit", Math.min(Math.max(limit, 1), MAX_ROWS)),
                (rs, rowNum) -> new AuditEntry(
                        rs.getLong("id"),
                        rs.getString("trace_id"),
                        rs.getLong("principal_id"),
                        rs.getString("principal_role"),
                        rs.getString("tool_name"),
                        rs.getString("risk_tier"),
                        rs.getString("arguments"),
                        rs.getString("decision"),
                        rs.getString("outcome"),
                        rs.getString("deny_reason"),
                        (Integer) rs.getObject("row_count"),
                        (Integer) rs.getObject("latency_ms"),
                        String.valueOf(rs.getObject("created_at"))));
    }

    @GetMapping("/approvals")
    public List<ApprovalSummary> approvals(@AuthenticationPrincipal BizUserDetails user,
                                           @RequestParam(defaultValue = "50") int limit) {

        BizPrincipal principal = user.toPrincipal("audit-console");
        auditWriter.recordAuditAccess(principal, "audit_query_approvals", TraceIds.next());

        // Note the columns that are absent: arguments and preview. An auditor
        // can see that a write was requested, by whom, and how it ended -
        // without seeing the customer data inside the request.
        return jdbc.query("""
                SELECT id, tool_name, status, requested_by, retry_count,
                       created_at, decided_at, executed_at
                FROM mcp_approval_request
                WHERE tenant_id = :tenantId
                ORDER BY created_at DESC
                LIMIT :limit
                """,
                new MapSqlParameterSource()
                        .addValue("tenantId", principal.tenantId())
                        .addValue("limit", Math.min(Math.max(limit, 1), MAX_ROWS)),
                (rs, rowNum) -> new ApprovalSummary(
                        rs.getString("id"),
                        rs.getString("tool_name"),
                        rs.getString("status"),
                        rs.getLong("requested_by"),
                        (Integer) rs.getObject("retry_count"),
                        String.valueOf(rs.getObject("created_at")),
                        String.valueOf(rs.getObject("decided_at")),
                        String.valueOf(rs.getObject("executed_at"))));
    }
}
