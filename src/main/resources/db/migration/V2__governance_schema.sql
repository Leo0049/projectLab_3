-- Governance tables (spec section 9.2).

CREATE TABLE mcp_audit_log (
    id             BIGSERIAL PRIMARY KEY,
    trace_id       VARCHAR(64) NOT NULL,
    principal_id   BIGINT      NOT NULL,
    principal_role VARCHAR(32) NOT NULL,
    tenant_id      BIGINT      NOT NULL,
    tool_name      VARCHAR(64) NOT NULL,
    risk_tier      VARCHAR(4)  NOT NULL,
    arguments      JSONB       NOT NULL,   -- masked, see spec section 7.2
    decision       VARCHAR(24) NOT NULL,   -- ALLOW / DENY / PENDING_APPROVAL
    outcome        VARCHAR(16),            -- SUCCESS / FAILED, written post-execution
    deny_reason    VARCHAR(64),
    row_count      INT,
    latency_ms     INT,
    client_name    VARCHAR(64),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_principal_time ON mcp_audit_log (principal_id, created_at DESC);
CREATE INDEX idx_audit_decision ON mcp_audit_log (decision, created_at DESC);
CREATE INDEX idx_audit_tenant_time ON mcp_audit_log (tenant_id, created_at DESC);

CREATE TABLE mcp_approval_request (
    id             VARCHAR(32) PRIMARY KEY,  -- apr_xxxx, doubles as the idempotency key
    audit_log_id   BIGINT      NOT NULL REFERENCES mcp_audit_log (id),
    tenant_id      BIGINT      NOT NULL,
    tool_name      VARCHAR(64) NOT NULL,
    arguments      JSONB       NOT NULL,     -- UNMASKED on purpose, see spec section 7.4
    preview        JSONB,
    requested_by   BIGINT      NOT NULL,
    status         VARCHAR(20) NOT NULL,     -- PENDING / APPROVED / EXECUTED /
                                             -- EXECUTION_FAILED / REJECTED / EXPIRED / ABANDONED
    approver_id    BIGINT,
    reject_reason  VARCHAR(255),
    failure_reason VARCHAR(255),
    retry_count    SMALLINT    NOT NULL DEFAULT 0,
    expires_at     TIMESTAMPTZ NOT NULL,
    decided_at     TIMESTAMPTZ,
    executed_at    TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_approval_status ON mcp_approval_request (status, expires_at);
CREATE INDEX idx_approval_tenant ON mcp_approval_request (tenant_id, created_at DESC);
