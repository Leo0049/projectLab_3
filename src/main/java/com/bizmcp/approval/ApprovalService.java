package com.bizmcp.approval;

import com.bizmcp.governance.GovernanceProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Drives the approval state machine (spec section 8.2).
 *
 * <p>Two rules shape this class:
 * <ul>
 *   <li><b>The id is the idempotency key.</b> Every transition locks the row
 *       first, so approving twice, double-clicking, or retrying concurrently
 *       still applies the write exactly once.</li>
 *   <li><b>Failures never retry themselves.</b> What a human approved was the
 *       preview; if the write failed the world has probably changed, so a
 *       person has to look again. Automatic retry would be approving something
 *       nobody saw.</li>
 * </ul>
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);
    private static final int REASON_MAX_LENGTH = 255;

    private final ApprovalRequestRepository repository;
    private final ApprovalExecutionRunner executionRunner;
    private final Map<String, ApprovalExecutor> executors;
    private final GovernanceProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public ApprovalService(ApprovalRequestRepository repository,
                           ApprovalExecutionRunner executionRunner,
                           List<ApprovalExecutor> executorList,
                           GovernanceProperties properties,
                           MeterRegistry meterRegistry,
                           Clock clock) {
        this.repository = repository;
        this.executionRunner = executionRunner;
        this.executors = executorList.stream()
                .collect(Collectors.toMap(ApprovalExecutor::toolName, Function.identity()));
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    public ApprovalExecutor executorFor(String toolName) {
        ApprovalExecutor executor = executors.get(toolName);
        if (executor == null) {
            throw new IllegalStateException("no approval executor registered for tool " + toolName);
        }
        return executor;
    }

    @Transactional
    public ApprovalRequest create(String approvalId,
                                  Long auditLogId,
                                  long tenantId,
                                  long requestedBy,
                                  String toolName,
                                  Map<String, Object> arguments,
                                  Map<String, Object> preview) {
        ApprovalRequest request = new ApprovalRequest();
        request.setId(approvalId);
        request.setAuditLogId(auditLogId);
        request.setTenantId(tenantId);
        request.setRequestedBy(requestedBy);
        request.setToolName(toolName);
        // Unmasked on purpose - the approver needs the real values (spec 7.4).
        request.setArguments(writeJson(arguments));
        request.setPreview(writeJson(preview));
        request.setStatus(ApprovalStatus.PENDING);
        request.setExpiresAt(now().plus(properties.getApprovalTtl()));
        request.setRetryCount((short) 0);

        meterRegistry.counter("mcp_approvals_total", "status", "PENDING").increment();
        return repository.save(request);
    }

    /**
     * Approves and immediately attempts the write.
     *
     * @return the request in its post-execution state (EXECUTED or EXECUTION_FAILED)
     */
    @Transactional
    public ApprovalRequest approve(String approvalId, long approverId, long tenantId) {
        ApprovalRequest request = lock(approvalId, tenantId);

        if (request.getStatus() == ApprovalStatus.EXECUTED) {
            return request; // already done; approving again must not re-apply
        }
        expireIfDue(request);
        requireTransition(request, ApprovalStatus.APPROVED);

        request.setStatus(ApprovalStatus.APPROVED);
        request.setApproverId(approverId);
        request.setDecidedAt(now());
        repository.saveAndFlush(request);

        return attemptExecution(request);
    }

    @Transactional
    public ApprovalRequest reject(String approvalId, long approverId, long tenantId, String reason) {
        ApprovalRequest request = lock(approvalId, tenantId);
        expireIfDue(request);
        requireTransition(request, ApprovalStatus.REJECTED);

        request.setStatus(ApprovalStatus.REJECTED);
        request.setApproverId(approverId);
        request.setDecidedAt(now());
        request.setRejectReason(truncate(reason));
        meterRegistry.counter("mcp_approvals_total", "status", "REJECTED").increment();
        return repository.save(request);
    }

    /** Human-triggered retry of a failed execution. */
    @Transactional
    public ApprovalRequest retry(String approvalId, long approverId, long tenantId) {
        ApprovalRequest request = lock(approvalId, tenantId);

        if (request.getStatus() == ApprovalStatus.EXECUTED) {
            return request;
        }
        if (request.getStatus() != ApprovalStatus.EXECUTION_FAILED) {
            throw new IllegalApprovalTransitionException(
                    "只有 EXECUTION_FAILED 的請求可以重試，目前狀態為 " + request.getStatus());
        }
        if (request.getRetryCount() >= properties.getMaxApprovalRetries()) {
            request.setStatus(ApprovalStatus.ABANDONED);
            meterRegistry.counter("mcp_approvals_total", "status", "ABANDONED").increment();
            return repository.save(request);
        }
        request.setRetryCount((short) (request.getRetryCount() + 1));
        request.setApproverId(approverId);
        repository.saveAndFlush(request);
        return attemptExecution(request);
    }

    private ApprovalRequest attemptExecution(ApprovalRequest request) {
        ApprovalExecutor executor = executorFor(request.getToolName());
        Map<String, Object> arguments = readJson(request.getArguments());
        try {
            executionRunner.run(executor, request.getTenantId(), arguments);
            request.setStatus(ApprovalStatus.EXECUTED);
            request.setExecutedAt(now());
            request.setFailureReason(null);
            meterRegistry.counter("mcp_approvals_total", "status", "EXECUTED").increment();
            log.info("approval {} executed", request.getId());
        } catch (RuntimeException e) {
            // Approved but not applied. Recorded explicitly so nobody has to
            // guess whether to retry (the gap v1.1 left open).
            boolean exhausted = request.getRetryCount() >= properties.getMaxApprovalRetries();
            request.setStatus(exhausted ? ApprovalStatus.ABANDONED : ApprovalStatus.EXECUTION_FAILED);
            request.setFailureReason(truncate(e.getMessage()));
            meterRegistry.counter("mcp_approvals_total",
                    "status", request.getStatus().name()).increment();
            log.warn("approval {} failed to execute: {}", request.getId(), e.getMessage());
        }
        return repository.save(request);
    }

    @Transactional(readOnly = true)
    public Optional<ApprovalRequest> find(String approvalId, long tenantId) {
        return repository.findById(approvalId)
                .filter(request -> request.getTenantId() == tenantId);
    }

    @Transactional(readOnly = true)
    public List<ApprovalRequest> pendingFor(long tenantId) {
        return repository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    /** Marks overdue requests EXPIRED so a stale approval cannot be clicked later. */
    @Transactional
    public int expireOverdue() {
        List<ApprovalRequest> expired = repository.findExpired(now());
        for (ApprovalRequest request : expired) {
            request.setStatus(ApprovalStatus.EXPIRED);
            meterRegistry.counter("mcp_approvals_total", "status", "EXPIRED").increment();
        }
        repository.saveAll(expired);
        return expired.size();
    }

    public ApprovalViews.ApprovalStatusView toView(ApprovalRequest request) {
        return new ApprovalViews.ApprovalStatusView(
                request.getId(),
                request.getToolName(),
                request.getStatus().name(),
                interpret(request),
                readJson(request.getPreview()),
                request.getFailureReason(),
                request.getRejectReason(),
                request.getRetryCount(),
                String.valueOf(request.getDecidedAt()),
                String.valueOf(request.getExecutedAt()));
    }

    /** Plain-language status so the model reports the truth, not an assumption. */
    private String interpret(ApprovalRequest request) {
        return switch (request.getStatus()) {
            case PENDING -> "尚未有人核准，操作還沒有生效。請使用者到 /approvals 處理。";
            case APPROVED -> "已核准，正在執行中。";
            case EXECUTED -> "已核准並成功執行，資料已變更。";
            case EXECUTION_FAILED -> "已核准但執行失敗，資料未變更。原因："
                                     + request.getFailureReason() + "。可請審批者重試。";
            case REJECTED -> "審批者已駁回，操作未執行。原因：" + request.getRejectReason();
            case EXPIRED -> "請求已逾時作廢，操作未執行。若仍需要請重新發起。";
            case ABANDONED -> "重試次數已達上限，操作放棄且資料未變更。";
        };
    }

    private ApprovalRequest lock(String approvalId, long tenantId) {
        ApprovalRequest request = repository.findByIdForUpdate(approvalId)
                .orElseThrow(() -> new ApprovalNotFoundException(approvalId));
        if (request.getTenantId() != tenantId) {
            // Do not reveal that it exists for another tenant.
            throw new ApprovalNotFoundException(approvalId);
        }
        return request;
    }

    private void expireIfDue(ApprovalRequest request) {
        if (request.isExpired(now())) {
            request.setStatus(ApprovalStatus.EXPIRED);
            repository.saveAndFlush(request);
        }
    }

    private void requireTransition(ApprovalRequest request, ApprovalStatus target) {
        if (!request.getStatus().canTransitionTo(target)) {
            throw new IllegalApprovalTransitionException(
                    "無法從 %s 轉換為 %s".formatted(request.getStatus(), target));
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= REASON_MAX_LENGTH ? value : value.substring(0, REASON_MAX_LENGTH);
    }

    public static String newApprovalId() {
        byte[] bytes = new byte[4];
        new java.security.SecureRandom().nextBytes(bytes);
        return "apr_" + HexFormat.of().formatHex(bytes);
    }

    private String writeJson(Map<String, Object> value) {
        Map<String, Object> safe = value == null ? new LinkedHashMap<>() : value;
        return jsonMapper.writeValueAsString(safe);
    }

    private Map<String, Object> readJson(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        return jsonMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    /** Raised when a transition is not legal in the state machine. */
    public static class IllegalApprovalTransitionException extends RuntimeException {
        public IllegalApprovalTransitionException(String message) {
            super(message);
        }
    }

    /** Raised when the request does not exist, or belongs to another tenant. */
    public static class ApprovalNotFoundException extends RuntimeException {
        public ApprovalNotFoundException(String approvalId) {
            super("找不到審批單 " + approvalId);
        }
    }
}
