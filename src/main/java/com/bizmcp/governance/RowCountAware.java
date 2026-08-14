package com.bizmcp.governance;

/**
 * Implemented by tool results that carry a row count, so the audit trail and
 * the {@code mcp_tool_rows_returned} metric can record volume without the
 * governance chain needing to understand each result shape.
 */
public interface RowCountAware {

    int rowCount();

    /** True when the result was capped by the row limit (spec section 9.4). */
    default boolean truncated() {
        return false;
    }
}
