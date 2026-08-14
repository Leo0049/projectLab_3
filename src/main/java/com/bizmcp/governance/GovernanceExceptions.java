package com.bizmcp.governance;

/**
 * Failures raised by the governance chain.
 *
 * <p>Messages are addressed to the model, not to a developer: they state the
 * next action a user could take and never expose a stack trace or internal
 * identifier (spec section 10).
 */
public final class GovernanceExceptions {

    private GovernanceExceptions() {
    }

    /**
     * Marks an exception whose message was written to be read by the model.
     *
     * <p>Everything else is replaced with a trace id before it leaves the
     * server. This matters more than it looks: Spring AI's tool callback
     * reports the <em>root cause</em> of a failure, so an unsanitised
     * exception hands the model — and therefore the user — raw internals such
     * as a PostgreSQL parser error or a connection string.
     */
    public interface ModelSafe {
    }

    /**
     * Tags a message as authored for the model.
     *
     * <p>Errors can also originate inside the framework, before the governance
     * chain is entered at all — argument binding is the common case, and it
     * reports things like "Conversion from JSON to java.time.LocalDate failed".
     * Those never pass through {@code sanitize()}, so the tool specification
     * wrapper takes the opposite default: anything reaching the client without
     * this tag is replaced with a trace id. The tag is stripped before the
     * message is sent, so a user never sees it.
     */
    public static final String MODEL_SAFE_TAG = "\u2063bizmcp\u2063";

    /**
     * The sanitised failure the model actually receives. Carries no cause, so
     * nothing downstream can unwrap it back to an internal message.
     */
    public static class ToolFailureException extends RuntimeException implements ModelSafe {
        public ToolFailureException(String message) {
            super(MODEL_SAFE_TAG + message, null, false, false);
        }
    }

    /** RBAC refused the call. */
    public static class ToolAccessDeniedException extends RuntimeException implements ModelSafe {
        private final String reasonCode;

        public ToolAccessDeniedException(Decision decision) {
            super(decision.message());
            this.reasonCode = decision.reasonCode();
        }

        public String reasonCode() {
            return reasonCode;
        }
    }

    /** Minute or day quota exhausted. */
    public static class RateLimitExceededException extends RuntimeException implements ModelSafe {
        private final long retryAfterSeconds;
        private final String window;

        public RateLimitExceededException(String window, long retryAfterSeconds) {
            super("已達%s速率上限，請於 %d 秒後重試。".formatted(window, retryAfterSeconds));
            this.window = window;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }

        public String window() {
            return window;
        }
    }

    /**
     * The audit record could not be written, so the call is refused.
     * This is the fail-closed rule from spec section 9.1: an unlogged
     * operation is treated as one that must not happen.
     */
    public static class AuditWriteFailedException extends RuntimeException implements ModelSafe {
        public AuditWriteFailedException(Throwable cause) {
            super("系統暫時無法處理此請求（稽核寫入失敗），操作已取消。", cause);
        }
    }

    /** A query template or tool signature violated the tenant isolation rules. */
    public static class TenantIsolationException extends RuntimeException implements ModelSafe {
        public TenantIsolationException(String message) {
            super(message);
        }
    }
}
