package com.bizmcp.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Retention policy (spec section 7.4).
 *
 * <p>The three windows are separate on purpose. Audit records are evidence and
 * are kept for a year; the approval table holds <em>unmasked</em> arguments,
 * because an approver has to see real values to make a real decision, so it is
 * the shortest-lived and is deleted outright rather than archived.
 */
@ConfigurationProperties(prefix = "bizmcp.retention")
public class RetentionProperties {

    private boolean enabled = true;

    /** How long audit records stay in the hot table before being archived. */
    private Duration auditHotWindow = Duration.ofDays(90);

    /** How long archived audit records are kept before their partition is dropped. */
    private Duration auditArchiveWindow = Duration.ofDays(365);

    /** How long approval requests, and their unmasked arguments, are kept. */
    private Duration approvalWindow = Duration.ofDays(90);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getAuditHotWindow() {
        return auditHotWindow;
    }

    public void setAuditHotWindow(Duration auditHotWindow) {
        this.auditHotWindow = auditHotWindow;
    }

    public Duration getAuditArchiveWindow() {
        return auditArchiveWindow;
    }

    public void setAuditArchiveWindow(Duration auditArchiveWindow) {
        this.auditArchiveWindow = auditArchiveWindow;
    }

    public Duration getApprovalWindow() {
        return approvalWindow;
    }

    public void setApprovalWindow(Duration approvalWindow) {
        this.approvalWindow = approvalWindow;
    }
}
