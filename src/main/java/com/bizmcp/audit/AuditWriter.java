package com.bizmcp.audit;

import com.bizmcp.governance.BizPrincipal;
import com.bizmcp.governance.Decision;
import com.bizmcp.governance.GovernanceExceptions.AuditWriteFailedException;
import com.bizmcp.governance.ToolCall;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * Writes the audit trail (spec sections 9.1, 9.2).
 *
 * <p><b>Fail-closed.</b> The pre-write happens before the tool runs, and if it
 * fails the call is refused. An operation with no record is treated as one
 * that must not happen.
 *
 * <p><b>Transaction boundary.</b> Every write runs in {@code REQUIRES_NEW} and
 * commits on its own. If the audit row shared the business transaction, a
 * rollback in the tool would take the audit record with it and "the failed
 * operation left no trace" — which is exactly the silent hole fail-closed is
 * meant to remove. This pairs with the aspect being ordered outside the
 * transaction advisor; see ADR-004.
 */
@Service
public class AuditWriter {

    private static final Logger log = LoggerFactory.getLogger(AuditWriter.class);

    private final AuditLogRepository repository;
    private final ArgumentMasker argumentMasker;
    private final MeterRegistry meterRegistry;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public AuditWriter(AuditLogRepository repository,
                       ArgumentMasker argumentMasker,
                       MeterRegistry meterRegistry) {
        this.repository = repository;
        this.argumentMasker = argumentMasker;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Records the intent to call a tool, before it runs.
     *
     * @return the audit row id, used to complete the record afterwards
     * @throws AuditWriteFailedException if the row cannot be persisted
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long recordPre(BizPrincipal principal, ToolCall call, String traceId) {
        return write(principal, call, traceId, "ALLOW", null);
    }

    /** Records a call that RBAC refused. Denials are evidence and must persist. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long recordDenial(BizPrincipal principal, ToolCall call, Decision decision, String traceId) {
        return write(principal, call, traceId, "DENY", decision.reasonCode());
    }

    /** Records a call refused by quota. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long recordRateLimited(BizPrincipal principal, ToolCall call, String traceId) {
        return write(principal, call, traceId, "DENY", "RATE_LIMITED");
    }

    private Long write(BizPrincipal principal, ToolCall call, String traceId,
                       String decision, String denyReason) {
        try {
            AuditLog entry = new AuditLog();
            entry.setTraceId(traceId);
            entry.setPrincipalId(principal.userId());
            entry.setPrincipalRole(principal.role().name());
            entry.setTenantId(principal.tenantId());
            entry.setToolName(call.toolName());
            entry.setRiskTier(call.tier().name());
            entry.setArguments(serialiseMaskedArguments(call.arguments()));
            entry.setDecision(decision);
            entry.setDenyReason(denyReason);
            entry.setClientName(principal.clientName());
            return repository.saveAndFlush(entry).getId();
        } catch (RuntimeException e) {
            meterRegistry.counter("mcp_audit_write_failures_total").increment();
            log.error("audit write failed for tool {} (trace {}) - refusing the call",
                    call.toolName(), traceId, e);
            throw new AuditWriteFailedException(e);
        }
    }

    /** Completes the record after a successful call. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(Long auditId, Integer rowCount, long latencyMs) {
        complete(auditId, "SUCCESS", rowCount, latencyMs, null);
    }

    /** Completes the record after the tool threw. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long auditId, Throwable error, long latencyMs) {
        complete(auditId, "FAILED", null, latencyMs, error);
    }

    /** Marks a write call as parked at the approval gate. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordPendingApproval(Long auditId, long latencyMs) {
        repository.findById(auditId).ifPresent(entry -> {
            entry.setDecision("PENDING_APPROVAL");
            entry.setLatencyMs((int) latencyMs);
            repository.save(entry);
        });
    }

    private void complete(Long auditId, String outcome, Integer rowCount, long latencyMs, Throwable error) {
        if (auditId == null) {
            return;
        }
        try {
            repository.findById(auditId).ifPresent(entry -> {
                entry.setOutcome(outcome);
                entry.setRowCount(rowCount);
                entry.setLatencyMs((int) latencyMs);
                repository.save(entry);
            });
        } catch (RuntimeException e) {
            // The pre-write already committed, so the call is accounted for.
            // Losing the completion detail must not mask the original error.
            meterRegistry.counter("mcp_audit_write_failures_total").increment();
            log.error("could not complete audit row {}", auditId, e);
        }
    }

    /**
     * Arguments are stringified before serialization. Audit writing must not be
     * the thing that breaks a call, so this deliberately avoids reflective
     * serialization of arbitrary argument types.
     */
    private String serialiseMaskedArguments(Map<String, Object> arguments) {
        Map<String, String> masked = argumentMasker.maskArguments(arguments);
        try {
            return jsonMapper.writeValueAsString(masked);
        } catch (RuntimeException e) {
            log.warn("could not serialise audit arguments, storing placeholder", e);
            return "{\"_error\":\"arguments could not be serialised\"}";
        }
    }
}
