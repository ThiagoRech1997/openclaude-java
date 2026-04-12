package dev.openclaude.llm.anthropic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.openclaude.core.model.*;
import dev.openclaude.llm.LlmClient;
import dev.openclaude.llm.LlmRequest;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Anthropic Messages API client with SSE streaming.
 * Calls POST /v1/messages with stream=true and parses server-sent events.
 */
public class AnthropicClient implements LlmClient {

    private static final String API_VERSION = "2023-06-01";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String apiKey;
    private final String baseUrl;
    private final HttpClient httpClient;

    public AnthropicClient(String apiKey, String baseUrl) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public String providerName() {
        return "anthropic";
    }

    @Override
    public void streamMessage(LlmRequest request, Consumer<StreamEvent> handler) {
        try {
            String body = buildRequestBody(request);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/messages"))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", API_VERSION)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofMinutes(10))
                    .build();

            HttpResponse<java.io.InputStream> response = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                handler.accept(new StreamEvent.Error(
                        "API error " + response.statusCode() + ": " + errorBody, null));
                return;
            }

            parseSseStream(response.body(), handler);
        } catch (Exception e) {
            handler.accept(new StreamEvent.Error(e.getMessage(), e));
        }
    }

    private String buildRequestBody(LlmRequest request) throws JsonProcessingException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", request.model());
        root.put("max_tokens", request.maxTokens());
        root.put("stream", true);

        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            root.put("system", request.systemPrompt());
        }

        if (request.tools() != null && !request.tools().isEmpty()) {
            root.set("tools", request.tools());
        }

        ArrayNode messagesArray = root.putArray("messages");
        for (Message msg : request.messages()) {
            ObjectNode msgNode = messagesArray.addObject();
            msgNode.put("role", msg.role().value());

            ArrayNode contentArray = msgNode.putArray("content");

            for (ContentBlock block : msg.content()) {
                ObjectNode n = contentArray.addObject();
                if (block instanceof ContentBlock.Text t) {
                    n.put("type", "text");
                    n.put("text", t.text());
                } else if (block instanceof ContentBlock.ToolUse tu) {
                    n.put("type", "tool_use");
                    n.put("id", tu.id());
                    n.put("name", tu.name());
                    n.set("input", tu.input());
                } else if (block instanceof ContentBlock.ToolResult tr) {
                    n.put("type", "tool_result");
                    n.put("tool_use_id", tr.toolUseId());
                    n.put("content", tr.content());
                    if (tr.isError()) n.put("is_error", true);
                } else if (block instanceof ContentBlock.Thinking th) {
                    n.put("type", "thinking");
                    n.put("thinking", th.thinking());
                }
            }
        }

        return MAPPER.writeValueAsString(root);
    }

    private void parseSseStream(java.io.InputStream inputStream, Consumer<StreamEvent> handler) {
        List<ContentBlock> contentBlocks = new ArrayList<>();
        StringBuilder currentToolJson = new StringBuilder();
        String currentToolId = null;
        String currentToolName = null;
        String stopReason = null;
        Usage totalUsage = Usage.ZERO;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            String eventType = null;

            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event: ")) {
                    eventType = line.substring(7).trim();
                } else if (line.startsWith("data: ") && eventType != null) {
                    String data = line.substring(6);
                    JsonNode json = MAPPER.readTree(data);

                    switch (eventType) {
                        case "message_start" -> {
                            JsonNode msg = json.get("message");
                            if (msg != null) {
                                String id = msg.path("id").asText("");
                                String model = msg.path("model").asText("");
                                Usage usage = parseUsage(msg.get("usage"));
                                totalUsage = totalUsage.add(usage);
                                handler.accept(new StreamEvent.MessageStart(id, model, usage));
                            }
                        }
                        case "content_block_start" -> {
                            JsonNode cb = json.get("content_block");
                            if (cb != null) {
                                String type = cb.path("type").asText();
                                if ("tool_use".equals(type)) {
                                    currentToolId = cb.path("id").asText();
                                    currentToolName = cb.path("name").asText();
                                    currentToolJson.setLength(0);
                                    handler.accept(new StreamEvent.ToolUseStart(currentToolId, currentToolName));
                                }
                            }
                        }
                        case "content_block_delta" -> {
                            JsonNode delta = json.get("delta");
                            if (delta != null) {
                                String type = delta.path("type").asText();
                                switch (type) {
                                    case "text_delta" -> {
                                        String text = delta.path("text").asText();
                                        handler.accept(new StreamEvent.TextDelta(text));
                                    }
                                    case "thinking_delta" -> {
                                        String thinking = delta.path("thinking").asText();
                                        handler.accept(new StreamEvent.ThinkingDelta(thinking));
                                    }
                                    case "input_json_delta" -> {
                                        String partial = delta.path("partial_json").asText();
                                        currentToolJson.append(partial);
                                        handler.accept(new StreamEvent.ToolInputDelta(partial));
                                    }
                                }
                            }
                        }
                        case "content_block_stop" -> {
                            int index = json.path("index").asInt();
                            if (currentToolId != null) {
                                JsonNode toolInput = currentToolJson.length() > 0
                                        ? MAPPER.readTree(currentToolJson.toString())
                                        : MAPPER.createObjectNode();
                                contentBlocks.add(new ContentBlock.ToolUse(
                                        currentToolId, currentToolName, toolInput));
                                currentToolId = null;
                                currentToolName = null;
                                currentToolJson.setLength(0);
                            }
                            handler.accept(new StreamEvent.ContentBlockStop(index));
                        }
                        case "message_delta" -> {
                            JsonNode delta = json.get("delta");
                            if (delta != null) {
                                stopReason = delta.path("stop_reason").asText(null);
                            }
                            JsonNode usageNode = json.get("usage");
                            if (usageNode != null) {
                                Usage deltaUsage = parseUsage(usageNode);
                                totalUsage = totalUsage.add(deltaUsage);
                            }
                            handler.accept(new StreamEvent.MessageDelta(stopReason, totalUsage));
                        }
                        case "message_stop" -> {
                            Message.AssistantMessage finalMessage = new Message.AssistantMessage(
                                    List.copyOf(contentBlocks), stopReason, totalUsage);
                            handler.accept(new StreamEvent.MessageComplete(finalMessage));
                        }
                        case "error" -> {
                            String errorMsg = json.path("error").path("message").asText("Unknown error");
                            handler.accept(new StreamEvent.Error(errorMsg, null));
                        }
                    }
                    eventType = null;
                }
                // Empty lines or lines starting with ':' are ignored (SSE spec)
            }
        } catch (Exception e) {
            handler.accept(new StreamEvent.Error("Stream parsing error: " + e.getMessage(), e));
        }
    }

    private Usage parseUsage(JsonNode node) {
        if (node == null) return Usage.ZERO;
        return new Usage(
                node.path("input_tokens").asInt(0),
                node.path("output_tokens").asInt(0),
                node.path("cache_creation_input_tokens").asInt(0),
                node.path("cache_read_input_tokens").asInt(0)
        );
    }
}
