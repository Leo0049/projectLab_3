package com.bizmcp.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/** A row in the audit trail (spec section 9.2). */
@Entity
@Table(name = "mcp_audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trace_id", nullable = false)
    private String traceId;

    @Column(name = "principal_id", nullable = false)
    private long principalId;

    @Column(name = "principal_role", nullable = false)
    private String principalRole;

    @Column(name = "tenant_id", nullable = false)
    private long tenantId;

    @Column(name = "tool_name", nullable = false)
    private String toolName;

    @Column(name = "risk_tier", nullable = false)
    private String riskTier;

    /** Masked before it is written: the log must not become the leak. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "arguments", nullable = false)
    private String arguments;

    @Column(name = "decision", nullable = false)
    private String decision;

    @Column(name = "outcome")
    private String outcome;

    @Column(name = "deny_reason")
    private String denyReason;

    @Column(name = "row_count")
    private Integer rowCount;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "client_name")
    private String clientName;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public long getPrincipalId() {
        return principalId;
    }

    public void setPrincipalId(long principalId) {
        this.principalId = principalId;
    }

    public String getPrincipalRole() {
        return principalRole;
    }

    public void setPrincipalRole(String principalRole) {
        this.principalRole = principalRole;
    }

    public long getTenantId() {
        return tenantId;
    }

    public void setTenantId(long tenantId) {
        this.tenantId = tenantId;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getRiskTier() {
        return riskTier;
    }

    public void setRiskTier(String riskTier) {
        this.riskTier = riskTier;
    }

    public String getArguments() {
        return arguments;
    }

    public void setArguments(String arguments) {
        this.arguments = arguments;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public String getDenyReason() {
        return denyReason;
    }

    public void setDenyReason(String denyReason) {
        this.denyReason = denyReason;
    }

    public Integer getRowCount() {
        return rowCount;
    }

    public void setRowCount(Integer rowCount) {
        this.rowCount = rowCount;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Integer latencyMs) {
        this.latencyMs = latencyMs;
    }

    public String getClientName() {
        return clientName;
    }

    public void setClientName(String clientName) {
        this.clientName = clientName;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
