package com.bizmcp.governance.startup;

import com.bizmcp.governance.GovernanceExceptions.TenantIsolationException;
import com.bizmcp.governance.ToolRisk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.aop.support.AopUtils;
import org.springframework.util.ClassUtils;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Startup checks on tool declarations (spec sections 7.3 and 5.3).
 *
 * <p>Both rules could be enforced at request time, but a tenant leak found at
 * request time has already happened. Making them boot failures converts two
 * classes of mistake — "forgot the risk annotation", "accepted a tenant as an
 * argument" — into something you cannot merge without noticing.
 *
 * <ol>
 *   <li>every {@code @McpTool} must declare {@link ToolRisk};</li>
 *   <li>no {@code @McpTool} may take a tenant-like parameter, so a model has
 *       no way to ask for another merchant's data;</li>
 *   <li>tool names must be unique.</li>
 * </ol>
 */
@Component
public class ToolGovernanceValidator implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(ToolGovernanceValidator.class);

    /**
     * Parameter names a tool may never expose. The tenant comes from the
     * access token; accepting it as an argument would put the boundary under
     * the model's control (spec section 3.2).
     */
    private static final Set<String> FORBIDDEN_PARAMETERS = Set.of(
            "tenantid", "tenant_id", "tenant",
            "merchantid", "merchant_id", "merchant",
            "orgid", "org_id", "companyid", "company_id");

    private final ConfigurableListableBeanFactory beanFactory;

    public ToolGovernanceValidator(ConfigurableListableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<String> violations = new ArrayList<>();
        Set<String> seenToolNames = new HashSet<>();
        int toolCount = 0;

        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            Class<?> type;
            try {
                type = beanFactory.getType(beanName);
            } catch (RuntimeException e) {
                continue;
            }
            if (type == null) {
                continue;
            }
            Class<?> targetType = ClassUtils.getUserClass(type);
            if (targetType == null) {
                continue;
            }

            for (Method method : targetType.getDeclaredMethods()) {
                McpTool tool = method.getAnnotation(McpTool.class);
                if (tool == null) {
                    continue;
                }
                toolCount++;
                String toolName = tool.name().isBlank() ? method.getName() : tool.name();

                if (!seenToolNames.add(toolName)) {
                    violations.add("duplicate tool name '%s' (%s)".formatted(toolName, method));
                }

                ToolRisk risk = method.getAnnotation(ToolRisk.class);
                if (risk == null) {
                    violations.add(
                            "tool '%s' has no @ToolRisk, so its risk tier and allowed roles are undeclared (%s)"
                                    .formatted(toolName, method));
                } else if (risk.allow().length == 0) {
                    violations.add(
                            "tool '%s' declares @ToolRisk with no allowed roles; if that is intended, remove the tool"
                                    .formatted(toolName));
                }

                for (Parameter parameter : method.getParameters()) {
                    String name = parameter.getName().toLowerCase(Locale.ROOT);
                    if (FORBIDDEN_PARAMETERS.contains(name)) {
                        violations.add(
                                "tool '%s' exposes tenant parameter '%s'; tenant must come from the principal only"
                                        .formatted(toolName, parameter.getName()));
                    }
                }
            }
        }

        if (!violations.isEmpty()) {
            throw new TenantIsolationException(
                    "tool governance validation failed:\n  - " + String.join("\n  - ", violations));
        }
        log.info("tool governance validation passed for {} tools", toolCount);
    }
}
