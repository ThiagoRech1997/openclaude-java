package dev.openclaude.llm.ollama;

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
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Ollama client using the /api/chat endpoint with streaming.
 * Ollama uses a JSONL (newline-delimited JSON) streaming format,
 * not SSE, so we parse it differently.
 *
 * Docs: https://github.com/ollama/ollama/blob/main/docs/api.md
 */
public class OllamaClient implements LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record ToolCallState(String id, String name, JsonNode arguments) {}

    private final String baseUrl;
    private final HttpClient httpClient;

    public OllamaClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public String providerName() {
        return "ollama";
    }

    @Override
    public void streamMessage(LlmRequest request, Consumer<StreamEvent> handler) {
        try {
            String body = buildRequestBody(request);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/chat"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofMinutes(10))
                    .build();

            HttpResponse<java.io.InputStream> response = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                handler.accept(new StreamEvent.Error(
                        "Ollama error " + response.statusCode() + ": " + errorBody, null));
                return;
            }

            parseJsonlStream(response.body(), handler);
        } catch (java.net.ConnectException e) {
            handler.accept(new StreamEvent.Error(
                    "Cannot connect to Ollama at " + baseUrl
                    + ". Is Ollama running? Start it with: ollama serve", e));
        } catch (Exception e) {
            handler.accept(new StreamEvent.Error(e.getMessage(), e));
        }
    }

    private String buildRequestBody(LlmRequest request) throws JsonProcessingException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", request.model());
        root.put("stream", true);

        // Options
        ObjectNode options = root.putObject("options");
        options.put("num_predict", request.maxTokens());

        // Messages
        ArrayNode messagesArray = root.putArray("messages");

        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            ObjectNode sysMsg = messagesArray.addObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", request.systemPrompt());
        }

        for (Message msg : request.messages()) {
            ObjectNode msgNode = messagesArray.addObject();
            msgNode.put("role", msg.role().value());

            StringBuilder text = new StringBuilder();
            for (ContentBlock block : msg.content()) {
                if (block instanceof ContentBlock.Text t) {
                    text.append(t.text());
                } else if (block instanceof ContentBlock.ToolResult tr) {
                    text.append("[Tool result for ").append(tr.toolUseId()).append("]: ");
                    for (ContentBlock inner : tr.content()) {
                        if (inner instanceof ContentBlock.Text innerText) {
                            text.append(innerText.text());
                        } else if (inner instanceof ContentBlock.Image img) {
                            text.append("[image: ").append(img.source().mediaType())
                                    .append(", base64 omitted]");
                        }
                    }
                }
            }
            msgNode.put("content", text.toString());
        }

        // Tools (Ollama supports OpenAI-compatible tool format since v0.3)
        if (request.tools() != null && !request.tools().isEmpty()) {
            ArrayNode toolsArray = root.putArray("tools");
            for (JsonNode tool : request.tools()) {
                ObjectNode oaiTool = toolsArray.addObject();
                oaiTool.put("type", "function");
                ObjectNode fn = oaiTool.putObject("function");
                fn.put("name", tool.path("name").asText());
                fn.put("description", tool.path("description").asText());
                fn.set("parameters", tool.get("input_schema"));
            }
        }

        return MAPPER.writeValueAsString(root);
    }

    /**
     * Parse Ollama's JSONL streaming format.
     * Each line is a JSON object with { message: { role, content }, done: bool }
     */
    private void parseJsonlStream(java.io.InputStream inputStream, Consumer<StreamEvent> handler) {
        List<ContentBlock> contentBlocks = new ArrayList<>();
        StringBuilder textAccum = new StringBuilder();
        Usage totalUsage = Usage.ZERO;
        List<ToolCallState> pendingToolCalls = new ArrayList<>();
        String stopReason = "end_turn";

        handler.accept(new StreamEvent.MessageStart("", "", Usage.ZERO));

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;

                JsonNode json = MAPPER.readTree(line);
                boolean done = json.path("done").asBoolean(false);

                JsonNode message = json.get("message");
                if (message != null && message.has("content")) {
                    String content = message.get("content").asText("");
                    if (!content.isEmpty()) {
                        handler.accept(new StreamEvent.TextDelta(content));
                        textAccum.append(content);
                    }
                }

                JsonNode toolCalls = message != null ? message.get("tool_calls") : null;
                if (toolCalls != null && toolCalls.isArray() && !toolCalls.isEmpty()) {
                    // Flush texto pendente antes de abrir tool_uses
                    if (textAccum.length() > 0) {
                        contentBlocks.add(new ContentBlock.Text(textAccum.toString()));
                        textAccum.setLength(0);
                    }
                    for (JsonNode tc : toolCalls) {
                        JsonNode fn = tc.get("function");
                        if (fn == null) continue;
                        String id = "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                        String name = fn.path("name").asText("");

                        // Ollama costuma mandar arguments como objeto JSON (não string),
                        // mas alguns modelos emitem string JSON. Cobrir ambos os casos.
                        JsonNode argsNode = fn.get("arguments");
                        if (argsNode == null) {
                            argsNode = MAPPER.createObjectNode();
                        } else if (argsNode.isTextual()) {
                            try {
                                argsNode = MAPPER.readTree(argsNode.asText());
                            } catch (Exception e) {
                                argsNode = MAPPER.createObjectNode();
                            }
                        }

                        handler.accept(new StreamEvent.ToolUseStart(id, name));
                        handler.accept(new StreamEvent.ToolInputDelta(MAPPER.writeValueAsString(argsNode)));
                        pendingToolCalls.add(new ToolCallState(id, name, argsNode));
                    }
                }

                if (done) {
                    // Extract usage from final message
                    int promptTokens = json.path("prompt_eval_count").asInt(0);
                    int completionTokens = json.path("eval_count").asInt(0);
                    totalUsage = new Usage(promptTokens, completionTokens, 0, 0);

                    if (textAccum.length() > 0) {
                        contentBlocks.add(new ContentBlock.Text(textAccum.toString()));
                        textAccum.setLength(0);
                    }
                    int idx = 0;
                    for (ToolCallState tcs : pendingToolCalls) {
                        contentBlocks.add(new ContentBlock.ToolUse(tcs.id(), tcs.name(), tcs.arguments()));
                        handler.accept(new StreamEvent.ContentBlockStop(idx++));
                    }
                    if (!pendingToolCalls.isEmpty()) {
                        stopReason = "tool_use";
                    }
                    break;
                }
            }
        } catch (Exception e) {
            handler.accept(new StreamEvent.Error("Stream parsing error: " + e.getMessage(), e));
            return;
        }

        handler.accept(new StreamEvent.MessageDelta(stopReason, totalUsage));

        if (contentBlocks.isEmpty()) {
            contentBlocks.add(new ContentBlock.Text(textAccum.toString()));
        }

        Message.AssistantMessage finalMsg = new Message.AssistantMessage(
                List.copyOf(contentBlocks), stopReason, totalUsage);
        handler.accept(new StreamEvent.MessageComplete(finalMsg));
    }
}
