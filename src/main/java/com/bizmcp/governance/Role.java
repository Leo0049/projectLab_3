package com.bizmcp.governance;

/**
 * Roles carried in the {@code role} claim of the access token.
 *
 * <p>Authorization is default-deny: a tool is callable only if it names the
 * role explicitly in {@link ToolRisk#allow()}. Adding a role here grants
 * nothing on its own.
 */
public enum Role {

    /** Runs a store; sees aggregates only, never customer records. */
    STORE_MANAGER,

    /**
     * Front-line customer service. Sees order detail, but the delivery address
     * stays masked — the role exists precisely so the address exemption on
     * CS_LEAD/ADMIN is a real distinction rather than a decorative one.
     */
    CS_AGENT,

    /** Customer service lead; sees order detail, and address unmasked. */
    CS_LEAD,

    /** Operations analyst; aggregates only. */
    ANALYST,

    /** Approves pending write requests in the approval console. */
    APPROVER,

    /** Reads the audit trail. Deliberately holds no MCP tool grants. */
    AUDITOR,

    /** Full access, including unmasked address. */
    ADMIN;

    public static Role fromClaim(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("token carries no role claim");
        }
        String normalised = value.trim().toUpperCase();
        if (normalised.startsWith("ROLE_")) {
            normalised = normalised.substring("ROLE_".length());
        }
        try {
            return Role.valueOf(normalised);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown role claim: " + value);
        }
    }

    public String authority() {
        return "ROLE_" + name();
    }
}
