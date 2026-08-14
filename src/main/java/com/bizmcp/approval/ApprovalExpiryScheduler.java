package com.bizmcp.approval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Expires overdue approvals (spec section 8.1, TTL).
 *
 * <p>Without this, a request raised half an hour ago could still be sitting on
 * screen and get approved by someone who has lost the context it was made in.
 */
@Component
public class ApprovalExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(ApprovalExpiryScheduler.class);

    private final ApprovalService approvalService;

    public ApprovalExpiryScheduler(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @Scheduled(fixedDelayString = "${bizmcp.governance.expiry-sweep-interval:PT1M}")
    public void expireOverdueRequests() {
        int expired = approvalService.expireOverdue();
        if (expired > 0) {
            log.info("expired {} overdue approval request(s)", expired);
        }
    }
}
