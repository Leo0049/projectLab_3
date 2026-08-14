package com.bizmcp.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the retention policy daily (spec section 7.4). */
@Component
@ConditionalOnProperty(prefix = "bizmcp.retention", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class AuditRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(AuditRetentionScheduler.class);

    private final AuditRetentionService retentionService;

    public AuditRetentionScheduler(AuditRetentionService retentionService) {
        this.retentionService = retentionService;
    }

    /** Off-peak by default; the archive move is a bulk write. */
    @Scheduled(cron = "${bizmcp.retention.cron:0 30 3 * * *}")
    public void applyRetentionPolicy() {
        AuditRetentionService.RetentionOutcome outcome = retentionService.apply();
        if (outcome.approvalsDeleted() > 0
            || outcome.auditRecordsArchived() > 0
            || outcome.partitionsDropped() > 0) {
            log.info("retention run: {} approvals deleted, {} audit records archived, "
                     + "{} partitions dropped",
                    outcome.approvalsDeleted(), outcome.auditRecordsArchived(),
                    outcome.partitionsDropped());
        }
    }
}
