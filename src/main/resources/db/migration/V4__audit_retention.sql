-- Audit retention (spec section 7.4).
--
-- The archive is range-partitioned by month, which is the point of partitioning
-- here: expiring a month becomes DROP TABLE rather than a large DELETE that
-- bloats the table and competes with live writes. Partitions are created on
-- demand by AuditRetentionService.

CREATE TABLE mcp_audit_log_archive (
    id             BIGINT      NOT NULL,
    trace_id       VARCHAR(64) NOT NULL,
    principal_id   BIGINT      NOT NULL,
    principal_role VARCHAR(32) NOT NULL,
    tenant_id      BIGINT      NOT NULL,
    tool_name      VARCHAR(64) NOT NULL,
    risk_tier      VARCHAR(4)  NOT NULL,
    arguments      JSONB       NOT NULL,   -- already masked when first written
    decision       VARCHAR(24) NOT NULL,
    outcome        VARCHAR(16),
    deny_reason    VARCHAR(64),
    row_count      INT,
    latency_ms     INT,
    client_name    VARCHAR(64),
    created_at     TIMESTAMPTZ NOT NULL,
    archived_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- A partitioned table's primary key must contain the partition key.
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

CREATE INDEX idx_audit_archive_tenant_time
    ON mcp_audit_log_archive (tenant_id, created_at DESC);
