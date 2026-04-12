package dev.openclaude.llm.openai;

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
import java.util.*;
import java.util.function.Consumer;

/**
 * OpenAI-compatible Chat Completions API client with SSE streaming.
 * Works with: OpenAI, Azure OpenAI, OpenRouter, DeepSeek, Groq,
 * Mistral, LM Studio, Together, and any OpenAI-compatible API.
 *
 * Maps Anthropic tool_use format ↔ OpenAI function_call format.
 */
public class OpenAIClient implements LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String apiKey;
    private final String baseUrl;
    private final HttpClient httpClient;

    public OpenAIClient(String apiKey, String baseUrl) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public String providerName() {
        return "openai";
    }

    @Override
    public void streamMessage(LlmRequest request, Consumer<StreamEvent> handler) {
        try {
            String body = buildRequestBody(request);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofMinutes(10))
                    .build();

            HttpResponse<java.io.InputStream> response = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                handler.accept(new StreamEvent.Error(
                        "OpenAI API error " + response.statusCode() + ": " + errorBody, null));
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

        // Build messages array in OpenAI format
        ArrayNode messagesArray = root.putArray("messages");

        // System message
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            ObjectNode sysMsg = messagesArray.addObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", request.systemPrompt());
        }

        // Conversation messages
        for (Message msg : request.messages()) {
            convertMessage(msg, messagesArray);
        }

        // Tools in OpenAI format: { type: "function", function: { name, description, parameters } }
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
     * Convert Anthropic-format messages to OpenAI format.
     */
    private void convertMessage(Message msg, ArrayNode messagesArray) throws JsonProcessingException {
        if (msg instanceof Message.UserMessage user) {
            // Check if content contains tool results
            boolean hasToolResults = user.content().stream()
                    .anyMatch(b -> b instanceof ContentBlock.ToolResult);

            if (hasToolResults) {
                // Tool results become individual "tool" role messages in OpenAI
                for (ContentBlock block : user.content()) {
                    if (block instanceof ContentBlock.ToolResult tr) {
                        ObjectNode toolMsg = messagesArray.addObject();
                        toolMsg.put("role", "tool");
                        toolMsg.put("tool_call_id", tr.toolUseId());
                        toolMsg.put("content", tr.content());
                    } else if (block instanceof ContentBlock.Text t) {
                        ObjectNode userMsg = messagesArray.addObject();
                        userMsg.put("role", "user");
                        userMsg.put("content", t.text());
                    }
                }
            } else {
                // Normal user message
                ObjectNode userMsg = messagesArray.addObject();
                userMsg.put("role", "user");
                StringBuilder text = new StringBuilder();
                for (ContentBlock block : user.content()) {
                    if (block instanceof ContentBlock.Text t) {
                        text.append(t.text());
                    }
                }
                userMsg.put("content", text.toString());
            }
        } else if (msg instanceof Message.AssistantMessage asst) {
            ObjectNode asstMsg = messagesArray.addObject();
            asstMsg.put("role", "assistant");

            // Check for tool calls
            List<ContentBlock.ToolUse> toolUses = new ArrayList<>();
            StringBuilder textContent = new StringBuilder();
            for (ContentBlock block : asst.content()) {
                if (block instanceof ContentBlock.ToolUse tu) {
                    toolUses.add(tu);
                } else if (block instanceof ContentBlock.Text t) {
                    textContent.append(t.text());
                }
            }

            if (!textContent.isEmpty()) {
                asstMsg.put("content", textContent.toString());
            } else {
                asstMsg.putNull("content");
            }

            if (!toolUses.isEmpty()) {
                ArrayNode toolCallsArray = asstMsg.putArray("tool_calls");
                for (ContentBlock.ToolUse tu : toolUses) {
                    ObjectNode tc = toolCallsArray.addObject();
                    tc.put("id", tu.id());
                    tc.put("type", "function");
                    ObjectNode fn = tc.putObject("function");
                    fn.put("name", tu.name());
                    fn.put("arguments", MAPPER.writeValueAsString(tu.input()));
                }
            }
        }
    }

    /**
     * Parse OpenAI SSE stream and emit Anthropic-compatible StreamEvents.
     */
    private void parseSseStream(java.io.InputStream inputStream, Consumer<StreamEvent> handler) {
        Map<Integer, String> toolCallIds = new HashMap<>();
        Map<Integer, String> toolCallNames = new HashMap<>();
        Map<Integer, StringBuilder> toolCallArgs = new HashMap<>();
        List<ContentBlock> contentBlocks = new ArrayList<>();
        StringBuilder textAccum = new StringBuilder();
        Usage totalUsage = Usage.ZERO;
        String stopReason = null;

        handler.accept(new StreamEvent.MessageStart("", "", Usage.ZERO));

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data: ")) continue;
                String data = line.substring(6).trim();
                if ("[DONE]".equals(data)) break;

                JsonNode json = MAPPER.readTree(data);
                JsonNode choices = json.get("choices");
                if (choices == null || choices.isEmpty()) continue;

                JsonNode choice = choices.get(0);
                JsonNode delta = choice.get("delta");
                String finishReason = choice.has("finish_reason") && !choice.get("finish_reason").isNull()
                        ? choice.get("finish_reason").asText() : null;

                if (delta != null) {
                    // Reasoning content (thinking) — emitted by Gemma, DeepSeek R1,
                    // Qwen QwQ, and other reasoning models via LM Studio / vLLM.
                    if (delta.has("reasoning_content") && !delta.get("reasoning_content").isNull()) {
                        String thinking = delta.get("reasoning_content").asText();
                        if (!thinking.isEmpty()) {
                            handler.accept(new StreamEvent.ThinkingDelta(thinking));
                        }
                    }

                    // Text content
                    if (delta.has("content") && !delta.get("content").isNull()) {
                        String text = delta.get("content").asText();
                        handler.accept(new StreamEvent.TextDelta(text));
                        textAccum.append(text);
                    }

                    // Tool calls
                    if (delta.has("tool_calls")) {
                        for (JsonNode tc : delta.get("tool_calls")) {
                            int index = tc.path("index").asInt(0);

                            if (tc.has("id") && !tc.get("id").isNull()) {
                                // New tool call starting
                                String id = tc.get("id").asText();
                                String name = tc.path("function").path("name").asText("");
                                toolCallIds.put(index, id);
                                toolCallNames.put(index, name);
                                toolCallArgs.put(index, new StringBuilder());

                                // Flush text before tool call
                                if (textAccum.length() > 0) {
                                    contentBlocks.add(new ContentBlock.Text(textAccum.toString()));
                                    textAccum.setLength(0);
                                }

                                handler.accept(new StreamEvent.ToolUseStart(id, name));
                            }

                            if (tc.has("function") && tc.get("function").has("arguments")) {
                                String args = tc.get("function").get("arguments").asText("");
                                StringBuilder sb = toolCallArgs.get(index);
                                if (sb != null) {
                                    sb.append(args);
                                }
                                handler.accept(new StreamEvent.ToolInputDelta(args));
                            }
                        }
                    }
                }

                if (finishReason != null) {
                    stopReason = mapFinishReason(finishReason);

                    // Finalize any pending tool calls
                    for (var entry : toolCallIds.entrySet()) {
                        int idx = entry.getKey();
                        String id = entry.getValue();
                        String name = toolCallNames.getOrDefault(idx, "");
                        String argsStr = toolCallArgs.containsKey(idx) ? toolCallArgs.get(idx).toString() : "{}";

                        JsonNode argsNode;
                        try {
                            argsNode = argsStr.isEmpty() ? MAPPER.createObjectNode() : MAPPER.readTree(argsStr);
                        } catch (Exception e) {
                            argsNode = MAPPER.createObjectNode();
                        }

                        contentBlocks.add(new ContentBlock.ToolUse(id, name, argsNode));
                        handler.accept(new StreamEvent.ContentBlockStop(idx));
                    }

                    // Flush remaining text
                    if (textAccum.length() > 0) {
                        contentBlocks.add(new ContentBlock.Text(textAccum.toString()));
                        textAccum.setLength(0);
                    }
                }

                // Usage info
                if (json.has("usage") && !json.get("usage").isNull()) {
                    JsonNode u = json.get("usage");
                    totalUsage = new Usage(
                            u.path("prompt_tokens").asInt(0),
                            u.path("completion_tokens").asInt(0),
                            0, 0);
                }
            }
        } catch (Exception e) {
            handler.accept(new StreamEvent.Error("Stream parsing error: " + e.getMessage(), e));
            return;
        }

        handler.accept(new StreamEvent.MessageDelta(stopReason, totalUsage));

        if (contentBlocks.isEmpty()) {
            contentBlocks.add(new ContentBlock.Text(""));
        }

        Message.AssistantMessage finalMsg = new Message.AssistantMessage(
                List.copyOf(contentBlocks), stopReason, totalUsage);
        handler.accept(new StreamEvent.MessageComplete(finalMsg));
    }

    /**
     * Map OpenAI finish_reason to Anthropic stop_reason.
     */
    private String mapFinishReason(String finishReason) {
        return switch (finishReason) {
            case "stop" -> "end_turn";
            case "tool_calls" -> "tool_use";
            case "length" -> "max_tokens";
            case "content_filter" -> "end_turn";
            default -> "end_turn";
        };
    }
}
