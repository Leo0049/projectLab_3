package com.bizmcp.suiteb;

import com.bizmcp.governance.BizAuthenticationToken;
import com.bizmcp.governance.BizPrincipal;
import com.bizmcp.governance.Role;
import com.bizmcp.support.McpToolInvoker;
import com.bizmcp.suiteb.EvalDataset.EvalQuestion;
import com.bizmcp.suiteb.EvalScorer.Observation;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Puts each evaluation question to a real model and records what it did.
 *
 * <p>The model is given the <em>registered</em> tool schemas and descriptions,
 * and its tool calls execute through the real governance chain. That matters:
 * it means the run measures the descriptions and policies as shipped, not a
 * parallel copy of them, and an injection question is answered by the same
 * tenant scoping that protects production.
 */
final class EvalRunner {

    /** Bounded so one confused conversation cannot spend the whole budget. */
    private static final int MAX_TURNS = 6;

    private final AnthropicMessagesClient client;
    private final McpToolInvoker invoker;
    private final List<McpServerFeatures.SyncToolSpecification> specifications;
    private final String evaluatedOn;

    EvalRunner(AnthropicMessagesClient client,
               McpToolInvoker invoker,
               List<McpServerFeatures.SyncToolSpecification> specifications,
               String evaluatedOn) {
        this.client = client;
        this.invoker = invoker;
        this.specifications = specifications;
        this.evaluatedOn = evaluatedOn;
    }

    /**
     * The evaluation identity: an admin of tenant 7, so every tool is reachable.
     *
     * <p>Suite B measures tool <em>selection</em>; permissions are Suite A's job.
     * Running as a restricted role would conflate "chose the wrong tool" with
     * "was refused", and make the score depend on the role rather than the
     * descriptions.
     */
    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(new BizAuthenticationToken(
                new BizPrincipal(44L, "carol", Role.ADMIN, 7L, "suite-b")));
    }

    private String systemPrompt() {
        return """
                你是一個連上企業營運系統的助理。使用者的問題請透過提供的工具回答。

                今天是 %s。相對日期（例如「上週」「這個月」）請依此推算。

                規則：
                - 只使用工具回傳的資料回答，不要臆測或自行推估數字。
                - 如果沒有任何工具能回答，就直說做不到，不要改用別的工具硬湊。
                - 寫入型工具不會立即生效，會回傳 PENDING_APPROVAL；
                  在用 check_approval_status 查到 EXECUTED 之前，不要說操作已完成。
                """.formatted(evaluatedOn);
    }

    /** Tool definitions in Messages API shape, from the registered MCP schemas. */
    private List<Map<String, Object>> toolDefinitions() {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (McpServerFeatures.SyncToolSpecification specification : specifications) {
            McpSchema.Tool tool = specification.tool();
            Map<String, Object> definition = new LinkedHashMap<>();
            definition.put("name", tool.name());
            definition.put("description", tool.description());
            definition.put("input_schema", tool.inputSchema());
            tools.add(definition);
        }
        return tools;
    }

    Observation run(EvalQuestion question) {
        authenticate();
        try {
            return converse(question);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private Observation converse(EvalQuestion question) {
        ArrayNode messages = client.json().createArrayNode();
        ObjectNode first = messages.addObject();
        first.put("role", "user");
        first.put("content", question.prompt());

        // Ordered and de-duplicated: "called it twice" is not a different answer
        // from "called it once", but the order it reached for tools is signal.
        LinkedHashSet<String> calledTools = new LinkedHashSet<>();
        Map<String, Object> firstArguments = new LinkedHashMap<>();

        for (int turn = 0; turn < MAX_TURNS; turn++) {
            ArrayNode content = client.send(systemPrompt(), toolDefinitions(), messages);

            List<JsonNode> toolUses = new ArrayList<>();
            content.forEach(block -> {
                if ("tool_use".equals(block.path("type").asString(""))) {
                    toolUses.add(block);
                }
            });
            if (toolUses.isEmpty()) {
                break;
            }

            ObjectNode assistant = messages.addObject();
            assistant.put("role", "assistant");
            assistant.set("content", content);

            ObjectNode userTurn = messages.addObject();
            userTurn.put("role", "user");
            ArrayNode results = userTurn.putArray("content");

            for (JsonNode toolUse : toolUses) {
                String name = toolUse.path("name").asString("");
                Map<String, Object> arguments = client.json()
                        .convertValue(toolUse.path("input"), LinkedHashMap.class);

                if (calledTools.isEmpty()) {
                    firstArguments.putAll(arguments);
                }
                calledTools.add(name);

                String payload = executeThroughGovernance(name, arguments);

                ObjectNode result = results.addObject();
                result.put("type", "tool_result");
                result.put("tool_use_id", toolUse.path("id").asString(""));
                result.put("content", payload);
            }
        }

        return new Observation(question.id(), List.copyOf(calledTools), firstArguments);
    }

    /**
     * Executes the call the model asked for, through the same chain a real
     * client would hit. A refusal is fed back as the tool result, so the model
     * has to deal with being denied rather than the harness hiding it.
     */
    private String executeThroughGovernance(String name, Map<String, Object> arguments) {
        try {
            return invoker.callForWirePayload(name, arguments);
        } catch (RuntimeException e) {
            return "工具呼叫失敗：" + e.getMessage();
        }
    }
}
