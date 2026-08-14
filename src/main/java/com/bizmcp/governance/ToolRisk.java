package com.bizmcp.governance;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a tool's risk tier and the roles allowed to call it.
 *
 * <p>This is the single source of truth for tool-level RBAC. It sits next to
 * the tool so a new tool cannot be added without stating its policy: startup
 * fails if an {@code @McpTool} method has no {@code @ToolRisk} (see
 * {@code ToolGovernanceValidator}), which makes "forgot to secure it" a
 * boot-time error rather than a production incident.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ToolRisk {

    RiskTier tier();

    /**
     * Roles permitted to call this tool. Empty means nobody, not everybody —
     * authorization is default-deny.
     */
    Role[] allow();
}
