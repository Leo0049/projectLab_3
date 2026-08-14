package com.bizmcp.governance;

import com.bizmcp.audit.AuditWriter;
import com.bizmcp.governance.GovernanceExceptions.ModelSafe;
import com.bizmcp.governance.GovernanceExceptions.RateLimitExceededException;
import com.bizmcp.governance.GovernanceExceptions.ToolFailureException;
import com.bizmcp.governance.GovernanceExceptions.ToolAccessDeniedException;
import com.bizmcp.governance.ratelimit.RateLimiter;
import com.bizmcp.masking.MaskingEngine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * The governance chain, applied to every {@code @McpTool} method
 * (spec section 5.3).
 *
 * <p>Security decisions live here rather than inside each tool, so adding a
 * tool cannot mean forgetting to secure it. A new tool needs only
 * {@code @McpTool} and {@link ToolRisk}; the chain then applies automatically.
 *
 * <p><b>Ordering is a correctness requirement, not style.</b> The aspect runs
 * at {@link Ordered#HIGHEST_PRECEDENCE}, outside Spring's transaction advisor.
 * If it ran inside, the pre-write audit row would join the business
 * transaction and a rollback would erase it — the fail-closed guarantee would
 * silently stop holding. {@code GovernanceOrderingTest} asserts this.
 *
 * <p><b>Why tool methods return {@code Object}.</b> The advice replaces the
 * result with an {@link UntrustedDataEnvelope}, and for a T3 call with a
 * pending-approval payload. A CGLIB proxy casts the advice's return value to
 * the method's declared type, so a tool declaring a concrete result type would
 * fail with a ClassCastException at call time. The concrete types still exist
 * and are still enforced — one layer down, in the service and DTO records.
 *
 * <p><b>Discovery caveat.</b> Proxying a tool bean hides {@code @McpTool} from
 * Spring AI's tool provider, which enumerates {@code getDeclaredMethods()} on
 * the proxy class. Without the fix in {@code GovernedToolProviderConfig} this
 * aspect would silently remove every tool from the server. See ADR-002.
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GovernanceAspect {

    private static final Logger log = LoggerFactory.getLogger(GovernanceAspect.class);

    private final PrincipalResolver principalResolver;
    private final RateLimiter rateLimiter;
    private final ToolAuthorizationVoter authorizationVoter;
    private final AuditWriter audit;
    private final ApprovalGate approvalGate;
    private final MaskingEngine maskingEngine;
    private final MeterRegistry meterRegistry;

    public GovernanceAspect(PrincipalResolver principalResolver,
                            RateLimiter rateLimiter,
                            ToolAuthorizationVoter authorizationVoter,
                            AuditWriter audit,
                            ApprovalGate approvalGate,
                            MaskingEngine maskingEngine,
                            MeterRegistry meterRegistry) {
        this.principalResolver = principalResolver;
        this.rateLimiter = rateLimiter;
        this.authorizationVoter = authorizationVoter;
        this.audit = audit;
        this.approvalGate = approvalGate;
        this.maskingEngine = maskingEngine;
        this.meterRegistry = meterRegistry;
    }

    @Around("@annotation(org.springframework.ai.mcp.annotation.McpTool)")
    public Object govern(ProceedingJoinPoint joinPoint) throws Throwable {
        ToolCall call = ToolCall.from(joinPoint);
        BizPrincipal principal = principalResolver.current();
        String traceId = TraceIds.next();

        // Every exit from the chain is sanitised, not just the tool's own
        // failures: a quota rejection or an audit-write failure would otherwise
        // reach the model with its internal cause attached.
        try {
            return runChain(joinPoint, call, principal, traceId);
        } catch (Throwable error) {
            throw sanitize(error, traceId, call);
        }
    }

    private Object runChain(ProceedingJoinPoint joinPoint,
                            ToolCall call,
                            BizPrincipal principal,
                            String traceId) throws Throwable {

        // 1. Quota. Charged before any work so a denied caller cannot use the
        //    server as a free query engine.
        try {
            rateLimiter.consumeOrThrow(principal, call.tier());
        } catch (RateLimitExceededException e) {
            audit.recordRateLimited(principal, call, traceId);
            countCall(call, "DENY");
            meterRegistry.counter("mcp_denials_total", "reason", "RATE_LIMITED").increment();
            throw e;
        }

        // 2. Tool-level RBAC, default-deny.
        Decision decision = authorizationVoter.vote(principal, call);
        if (decision.denied()) {
            audit.recordDenial(principal, call, decision, traceId);
            countCall(call, "DENY");
            meterRegistry.counter("mcp_denials_total", "reason", decision.reasonCode()).increment();
            log.info("denied {} for {} ({}): {}",
                    call.toolName(), principal.username(), principal.role(), decision.reasonCode());
            throw new ToolAccessDeniedException(decision);
        }

        // 3. Audit pre-write, committed independently. Fail-closed: if this
        //    throws, the call never runs.
        Long auditId = audit.recordPre(principal, call, traceId);

        long startedAt = System.nanoTime();

        // 4. Writes stop here and wait for a human.
        if (call.tier().requiresApproval()) {
            Object pending = approvalGate.enqueue(auditId, principal, call);
            audit.recordPendingApproval(auditId, elapsedMillis(startedAt));
            countCall(call, "PENDING_APPROVAL");
            return UntrustedDataEnvelope.wrap(call.toolName(), pending);
        }

        try {
            Object result = joinPoint.proceed();
            long latencyMs = elapsedMillis(startedAt);

            // 5. Mask before the framework serializes. This is the step that
            //    makes masking independent of which ObjectMapper is used
            //    (ADR-003) — the object handed onward is already masked.
            Object masked = maskingEngine.mask(result, principal.role());

            Integer rowCount = rowCountOf(result);
            audit.recordSuccess(auditId, rowCount, latencyMs);
            recordSuccessMetrics(call, rowCount, latencyMs, result);

            return UntrustedDataEnvelope.wrap(call.toolName(), masked);
        } catch (Throwable error) {
            long latencyMs = elapsedMillis(startedAt);
            audit.recordFailure(auditId, error, latencyMs);
            countCall(call, "ERROR");
            throw error;
        }
    }

    /**
     * Converts a failure into something safe to show the model (spec section 10).
     *
     * <p>Spring AI reports a tool failure's <em>root cause</em>, so returning the
     * raw exception would hand the model internal detail — a PostgreSQL parser
     * message, a constraint name, a connection string. Messages we wrote for the
     * model are passed through; everything else becomes a trace id that ties the
     * user's report back to the log without telling them anything about the
     * inside of the system.
     */
    private RuntimeException sanitize(Throwable error, String traceId, ToolCall call) {
        // Argument validation messages are authored to help the model self-correct.
        if (error instanceof ModelSafe || error instanceof IllegalArgumentException) {
            return new ToolFailureException(error.getMessage());
        }
        log.error("internal failure in tool {} (trace {})", call.toolName(), traceId, error);
        return new ToolFailureException(
                "系統暫時無法處理此請求，追蹤編號 %s。請稍後再試，或將此編號提供給系統管理員。"
                        .formatted(traceId));
    }

    private void recordSuccessMetrics(ToolCall call, Integer rowCount, long latencyMs, Object result) {
        countCall(call, "ALLOW");
        Timer.builder("mcp_tool_latency_seconds")
                .tag("tool", call.toolName())
                .register(meterRegistry)
                .record(latencyMs, TimeUnit.MILLISECONDS);
        if (rowCount != null) {
            meterRegistry.summary("mcp_tool_rows_returned", "tool", call.toolName()).record(rowCount);
        }
        if (result instanceof RowCountAware aware && aware.truncated()) {
            meterRegistry.counter("mcp_result_truncated_total", "tool", call.toolName()).increment();
        }
    }

    private void countCall(ToolCall call, String decision) {
        meterRegistry.counter("mcp_tool_calls_total",
                "tool", call.toolName(),
                "tier", call.tier().name(),
                "decision", decision).increment();
    }

    private Integer rowCountOf(Object result) {
        if (result instanceof RowCountAware aware) {
            return aware.rowCount();
        }
        if (result instanceof java.util.Collection<?> collection) {
            return collection.size();
        }
        return result == null ? 0 : 1;
    }

    private long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }
}
