package com.bizmcp.spike;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.provider.tool.SyncMcpToolProvider;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spike 1 (spec sections 5.3 and 14), kept as a regression test.
 *
 * <p>The question was whether Spring AOP can intercept {@code @McpTool}
 * methods. The answer turned out to be "yes, but only after fixing tool
 * discovery" — and the failure mode is silent, which is what makes it
 * dangerous: proxying a tool bean makes Spring AI find <em>zero</em> tools,
 * with no error, so the server starts and simply advertises nothing.
 *
 * <p>These assertions pin both halves. If a future Spring AI release changes
 * either, this fails loudly and {@code GovernedMcpSpecificationConfig} needs
 * revisiting. See ADR-002.
 */
class Spike1AopInterceptionTest {

    static final List<String> INTERCEPTED = new ArrayList<>();

    public static class SalesTools {
        @McpTool(name = "query_sales_summary", description = "sales summary")
        public String query(@McpToolParam(description = "group by") String groupBy) {
            return "rows-for-" + groupBy;
        }
    }

    @Aspect
    public static class GovernanceAspect {
        @Around("@annotation(org.springframework.ai.mcp.annotation.McpTool)")
        public Object govern(ProceedingJoinPoint joinPoint) throws Throwable {
            INTERCEPTED.add(joinPoint.getSignature().getName());
            return joinPoint.proceed() + "|masked";
        }
    }

    @Configuration
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class Config {
        @Bean SalesTools salesTools() {
            return new SalesTools();
        }

        @Bean GovernanceAspect governanceAspect() {
            return new GovernanceAspect();
        }
    }

    @Test
    void proxyingATooldBeanHidesTheAnnotationFromTheStockProvider() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            Object bean = context.getBean(SalesTools.class);

            assertThat(AopUtils.isAopProxy(bean)).isTrue();

            // The CGLIB override does not inherit @McpTool...
            Method proxyMethod = java.util.Arrays.stream(bean.getClass().getDeclaredMethods())
                    .filter(method -> method.getName().equals("query"))
                    .findFirst()
                    .orElseThrow();
            assertThat(proxyMethod.getAnnotation(McpTool.class)).isNull();

            // ...so the stock provider, which scans getDeclaredMethods() on the
            // proxy class, registers nothing at all.
            assertThat(new SyncMcpToolProvider(List.of(bean)).getToolSpecifications())
                    .as("stock provider silently finds no tools behind a proxy")
                    .isEmpty();
        }
    }

    @Test
    void resolvingTheTargetClassRestoresDiscoveryAndLetsTheAspectRun() {
        INTERCEPTED.clear();
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            Object bean = context.getBean(SalesTools.class);

            // The fix used in GovernedMcpSpecificationConfig.
            List<McpServerFeatures.SyncToolSpecification> specifications =
                    new SyncMcpToolProvider(List.of(bean)) {
                        @Override
                        protected Method[] doGetClassMethods(Object toolBean) {
                            return AopUtils.getTargetClass(toolBean).getDeclaredMethods();
                        }
                    }.getToolSpecifications();

            assertThat(specifications).hasSize(1);

            McpSchema.CallToolResult result = specifications.get(0).callHandler()
                    .apply(null, new McpSchema.CallToolRequest(
                            "query_sales_summary", Map.of("groupBy", "STORE")));

            assertThat(INTERCEPTED).containsExactly("query");

            String text = result.content().stream()
                    .filter(McpSchema.TextContent.class::isInstance)
                    .map(content -> ((McpSchema.TextContent) content).text())
                    .reduce("", String::concat);

            // Spike 2's other half: the advice's return value is what gets
            // serialized, so masking applied in the advice reaches the wire
            // regardless of which ObjectMapper the framework uses.
            assertThat(text).contains("rows-for-STORE|masked");
        }
    }
}
