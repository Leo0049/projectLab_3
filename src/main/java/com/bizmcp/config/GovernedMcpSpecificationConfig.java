package com.bizmcp.config;

import com.bizmcp.governance.GovernanceExceptions;
import com.bizmcp.governance.TraceIds;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.provider.resource.SyncMcpResourceProvider;
import org.springframework.ai.mcp.annotation.provider.tool.SyncMcpToolProvider;
import org.springframework.aop.support.AopUtils;
import org.springframework.util.ClassUtils;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the MCP tool and resource specifications, replacing Spring AI's
 * annotation scanner (disabled via
 * {@code spring.ai.mcp.server.annotation-scanner.enabled=false}).
 *
 * <p><b>Why this class exists.</b> Spike 1 found that Spring AI 2.0.0
 * enumerates tool methods with {@code bean.getClass().getDeclaredMethods()}.
 * When the governance aspect proxies a tool bean, that returns the CGLIB
 * subclass's overrides — and an overriding method does not inherit
 * {@code @McpTool}. The framework therefore finds <em>zero</em> tools, with no
 * error and no log line: the server starts, advertises nothing, and every
 * governance test that goes through the tool layer silently passes for the
 * wrong reason.
 *
 * <p>The fix is to resolve the ultimate target class before scanning. It is
 * three lines, but finding it was the point of running the spike. See ADR-002,
 * and {@code Spike1AopInterceptionTest} which pins the behaviour so a Spring AI
 * upgrade that changes it fails loudly.
 */
@Configuration(proxyBeanMethods = false)
public class GovernedMcpSpecificationConfig {

    private static final Logger log = LoggerFactory.getLogger(GovernedMcpSpecificationConfig.class);

    // @Primary because ToolCallbackConverterAutoConfiguration also contributes a
    // (here empty) List<SyncToolSpecification> built from ToolCallback beans.
    @Bean
    @Primary
    public List<McpServerFeatures.SyncToolSpecification> governedToolSpecifications(
            ConfigurableListableBeanFactory beanFactory) {

        List<Object> toolBeans = beansDeclaring(beanFactory, McpTool.class);

        SyncMcpToolProvider provider = new SyncMcpToolProvider(toolBeans) {
            @Override
            protected Method[] doGetClassMethods(Object bean) {
                return AopUtils.getTargetClass(bean).getDeclaredMethods();
            }
        };

        List<McpServerFeatures.SyncToolSpecification> specifications =
                provider.getToolSpecifications().stream()
                        .map(GovernedMcpSpecificationConfig::withSanitisedErrors)
                        .toList();
        if (toolBeans.isEmpty() || specifications.isEmpty()) {
            // The exact failure mode Spike 1 uncovered. Never let it be silent.
            throw new IllegalStateException(
                    "no MCP tools were discovered - governance proxying has broken tool discovery");
        }
        log.info("registered {} governed MCP tools from {} beans",
                specifications.size(), toolBeans.size());
        return specifications;
    }

    @Bean
    @Primary
    public List<McpServerFeatures.SyncResourceSpecification> governedResourceSpecifications(
            ConfigurableListableBeanFactory beanFactory) {

        List<Object> resourceBeans = beansDeclaring(beanFactory, McpResource.class);
        if (resourceBeans.isEmpty()) {
            return List.of();
        }
        SyncMcpResourceProvider provider = new SyncMcpResourceProvider(resourceBeans) {
            @Override
            protected Method[] doGetClassMethods(Object bean) {
                return AopUtils.getTargetClass(bean).getDeclaredMethods();
            }
        };
        return provider.getResourceSpecifications();
    }

    /**
     * Wraps a tool specification so no error text escapes unreviewed.
     *
     * <p>The governance chain sanitises what it raises, but it cannot see
     * failures that happen before it is entered — argument binding is the
     * obvious one, and it happily reports internal Java type names. Rather than
     * enumerate those cases, this inverts the default: an error is forwarded
     * only if it carries the model-safe tag, and anything else becomes a trace
     * id that is useless to an attacker and sufficient for support.
     */
    private static McpServerFeatures.SyncToolSpecification withSanitisedErrors(
            McpServerFeatures.SyncToolSpecification specification) {

        var delegate = specification.callHandler();
        String toolName = specification.tool().name();

        java.util.function.BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult>
                guarded = (exchange, request) -> {
            McpSchema.CallToolResult result;
            try {
                result = delegate.apply(exchange, request);
            } catch (RuntimeException e) {
                return opaqueFailure(toolName, e);
            }
            if (!Boolean.TRUE.equals(result.isError())) {
                return result;
            }
            return reviewErrorText(result, toolName);
        };

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(specification.tool())
                .callHandler(guarded)
                .build();
    }

    private static McpSchema.CallToolResult reviewErrorText(McpSchema.CallToolResult result,
                                                            String toolName) {
        StringBuilder combined = new StringBuilder();
        for (McpSchema.Content content : result.content()) {
            if (content instanceof McpSchema.TextContent text) {
                combined.append(text.text());
            }
        }
        String text = combined.toString();
        int tagAt = text.indexOf(GovernanceExceptions.MODEL_SAFE_TAG);
        if (tagAt < 0) {
            return opaqueFailure(toolName, new IllegalStateException(text));
        }
        // Drop the framework's prefix and the tag itself.
        String message = text.substring(tagAt + GovernanceExceptions.MODEL_SAFE_TAG.length());
        return McpSchema.CallToolResult.builder().addTextContent(message).isError(true).build();
    }

    private static McpSchema.CallToolResult opaqueFailure(String toolName, Exception cause) {
        String traceId = TraceIds.next();
        log.error("unsanitised failure in tool {} (trace {})", toolName, traceId, cause);
        return McpSchema.CallToolResult.builder()
                .addTextContent("系統暫時無法處理此請求，追蹤編號 %s。請確認參數格式後重試，"
                                .formatted(traceId)
                                + "或將此編號提供給系統管理員。")
                .isError(true)
                .build();
    }

    /**
     * Finds beans declaring the given MCP annotation, inspecting the target
     * class so proxied beans are still recognised. Types are resolved before
     * instantiation so unrelated lazy beans are not forced into existence.
     */
    private List<Object> beansDeclaring(ConfigurableListableBeanFactory beanFactory,
                                        Class<? extends Annotation> annotation) {
        List<Object> found = new ArrayList<>();
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
            if (targetType == null || !declaresAnnotatedMethod(targetType, annotation)) {
                continue;
            }
            found.add(beanFactory.getBean(beanName));
        }
        return found;
    }

    private boolean declaresAnnotatedMethod(Class<?> type, Class<? extends Annotation> annotation) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.isAnnotationPresent(annotation)) {
                return true;
            }
        }
        return false;
    }
}
