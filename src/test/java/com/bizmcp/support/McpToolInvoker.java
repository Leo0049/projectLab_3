package com.bizmcp.support;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Calls tools the way the MCP protocol does: through the registered
 * {@code SyncToolSpecification}, by tool name, with a map of named arguments.
 *
 * <p>Suite A goes through this rather than calling service methods, so the
 * assertions cover the whole path — argument binding, the governance chain,
 * masking, and serialization to the wire payload. Testing the DTO instead
 * would prove nothing about what actually leaves the server, which is the
 * mistake spec section 7.2 warns about.
 */
@Component
public class McpToolInvoker {

    private final List<McpServerFeatures.SyncToolSpecification> toolSpecifications;

    public McpToolInvoker(List<McpServerFeatures.SyncToolSpecification> toolSpecifications) {
        this.toolSpecifications = toolSpecifications;
    }

    public List<String> toolNames() {
        return toolSpecifications.stream().map(spec -> spec.tool().name()).sorted().toList();
    }

    public McpServerFeatures.SyncToolSpecification specification(String toolName) {
        return toolSpecifications.stream()
                .filter(spec -> spec.tool().name().equals(toolName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("tool not registered: " + toolName
                                                      + " (registered: " + toolNames() + ")"));
    }

    /** Invokes a tool and returns the raw MCP result. */
    public McpSchema.CallToolResult call(String toolName, Map<String, Object> arguments) {
        return specification(toolName).callHandler()
                .apply(null, new McpSchema.CallToolRequest(toolName, arguments));
    }

    /**
     * The serialized wire payload for a call: the exact text an MCP client
     * receives. Masking assertions run against this string.
     */
    public String callForWirePayload(String toolName, Map<String, Object> arguments) {
        McpSchema.CallToolResult result = call(toolName, arguments);
        StringBuilder text = new StringBuilder();
        for (McpSchema.Content content : result.content()) {
            if (content instanceof McpSchema.TextContent textContent) {
                text.append(textContent.text());
            }
        }
        return text.toString();
    }

    public boolean isError(McpSchema.CallToolResult result) {
        return Boolean.TRUE.equals(result.isError());
    }
}
