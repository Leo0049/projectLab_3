package com.bizmcp.governance;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single tool invocation: the tool's name, declared policy, and named
 * arguments. Parameter names come from {@code -parameters} (set in the build),
 * so the audit trail records {@code startDate=2026-01-01} rather than
 * {@code arg0=2026-01-01}.
 */
public record ToolCall(
        String toolName,
        RiskTier tier,
        Role[] allowedRoles,
        Map<String, Object> arguments,
        Method method
) {

    public static ToolCall from(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = resolveTargetMethod(joinPoint, signature);

        McpTool tool = AnnotatedElementUtils.findMergedAnnotation(method, McpTool.class);
        ToolRisk risk = AnnotatedElementUtils.findMergedAnnotation(method, ToolRisk.class);
        if (tool == null || risk == null) {
            throw new IllegalStateException(
                    "governed method is missing @McpTool/@ToolRisk: " + method);
        }

        String toolName = tool.name().isBlank() ? method.getName() : tool.name();

        String[] names = signature.getParameterNames();
        Object[] values = joinPoint.getArgs();
        Map<String, Object> arguments = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i++) {
            String name = (names != null && i < names.length && names[i] != null) ? names[i] : "arg" + i;
            arguments.put(name, values[i]);
        }

        return new ToolCall(toolName, risk.tier(), risk.allow(), arguments, method);
    }

    /**
     * The join point reports the interface/proxy method; governance policy is
     * declared on the implementation, so resolve back to the target class.
     */
    private static Method resolveTargetMethod(ProceedingJoinPoint joinPoint, MethodSignature signature) {
        Class<?> targetClass = joinPoint.getTarget().getClass();
        try {
            return targetClass.getDeclaredMethod(signature.getName(), signature.getParameterTypes());
        } catch (NoSuchMethodException e) {
            return signature.getMethod();
        }
    }

    public Object argument(String name) {
        return arguments.get(name);
    }
}
