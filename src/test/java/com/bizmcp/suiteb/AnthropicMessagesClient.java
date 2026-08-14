package com.bizmcp.suiteb;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * A small Messages API client for the evaluation harness.
 *
 * <p>Written by hand rather than pulling in an SDK: the harness needs one
 * endpoint and tool-use handling, and adding a client library to the
 * production dependency tree to run a nightly test would be the wrong trade.
 */
final class AnthropicMessagesClient {

    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    private static final String API_VERSION = "2023-06-01";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            // Honour https.proxyHost and friends, which build environments set.
            .proxy(ProxySelector.getDefault())
            .build();

    private final JsonMapper json = JsonMapper.builder().build();
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int maxTokens;

    AnthropicMessagesClient(String apiKey, String baseUrl, String model, int maxTokens) {
        this.apiKey = apiKey;
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.trim();
        this.model = model;
        this.maxTokens = maxTokens;
    }

    static String apiKeyFromEnvironment() {
        String key = System.getenv("ANTHROPIC_API_KEY");
        return (key == null || key.isBlank()) ? null : key.trim();
    }

    static String baseUrlFromEnvironment() {
        return System.getenv("ANTHROPIC_BASE_URL");
    }

    static String modelFromEnvironment() {
        String model = System.getenv("BIZMCP_EVAL_MODEL");
        return (model == null || model.isBlank()) ? "claude-sonnet-5" : model.trim();
    }

    /**
     * One turn.
     *
     * @param tools tool definitions, straight from the registered MCP schemas
     *              so the model sees exactly what a real client would
     * @return the assistant's content blocks
     */
    ArrayNode send(String systemPrompt, List<Map<String, Object>> tools, ArrayNode messages) {
        ObjectNode body = json.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("system", systemPrompt);
        body.set("messages", messages);

        ArrayNode toolArray = body.putArray("tools");
        for (Map<String, Object> tool : tools) {
            toolArray.add(json.valueToTree(tool));
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/messages"))
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .header("content-type", "application/json")
                .timeout(Duration.ofMinutes(2))
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("Messages API call failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while calling the Messages API", e);
        }

        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException(
                    "Messages API returned " + response.statusCode() + ": " + response.body());
        }

        JsonNode parsed = json.readTree(response.body());
        JsonNode content = parsed.get("content");
        if (content == null || !content.isArray()) {
            throw new IllegalStateException("unexpected Messages API response: " + response.body());
        }
        return (ArrayNode) content;
    }

    JsonMapper json() {
        return json;
    }

    String model() {
        return model;
    }
}
