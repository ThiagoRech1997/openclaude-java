package dev.openclaude.core.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.openclaude.core.model.ContentBlock;
import dev.openclaude.core.model.Message;
import dev.openclaude.core.model.Usage;

import java.util.ArrayList;
import java.util.List;

/**
 * JSON round-trip for the sealed Message/ContentBlock hierarchy, used by session
 * persistence. Serialization leans on the records' own {@code @JsonProperty}
 * shape (Anthropic API format); deserialization dispatches on the {@code type}
 * discriminator.
 */
final class SessionCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SessionCodec() {}

    static ObjectNode messageToJson(Message msg) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("role", msg.role().value());
        ArrayNode content = node.putArray("content");
        for (ContentBlock block : msg.content()) {
            content.add(MAPPER.<JsonNode>valueToTree(block));
        }
        if (msg instanceof Message.AssistantMessage am) {
            if (am.stopReason() != null) {
                node.put("stop_reason", am.stopReason());
            }
            if (am.usage() != null) {
                node.set("usage", usageToJson(am.usage()));
            }
        }
        return node;
    }

    static Message messageFromJson(JsonNode node) {
        List<ContentBlock> blocks = new ArrayList<>();
        for (JsonNode b : node.path("content")) {
            blocks.add(blockFromJson(b));
        }
        String role = node.path("role").asText("user");
        if ("assistant".equals(role)) {
            String stopReason = node.hasNonNull("stop_reason")
                    ? node.get("stop_reason").asText() : null;
            Usage usage = node.has("usage") ? usageFromJson(node.get("usage")) : Usage.ZERO;
            return new Message.AssistantMessage(blocks, stopReason, usage);
        }
        return new Message.UserMessage(blocks);
    }

    static ContentBlock blockFromJson(JsonNode node) {
        String type = node.path("type").asText("");
        return switch (type) {
            case "text" -> new ContentBlock.Text(node.path("text").asText(""));
            case "thinking" -> new ContentBlock.Thinking(node.path("thinking").asText(""));
            case "tool_use" -> new ContentBlock.ToolUse(
                    node.path("id").asText(""),
                    node.path("name").asText(""),
                    node.has("input") && !node.get("input").isNull()
                            ? node.get("input") : MAPPER.createObjectNode());
            case "tool_result" -> {
                List<ContentBlock> inner = new ArrayList<>();
                for (JsonNode c : node.path("content")) {
                    inner.add(blockFromJson(c));
                }
                yield new ContentBlock.ToolResult(
                        node.path("tool_use_id").asText(""),
                        inner,
                        node.path("is_error").asBoolean(false));
            }
            case "image" -> new ContentBlock.Image(new ContentBlock.Image.Source(
                    node.path("source").path("type").asText("base64"),
                    node.path("source").path("media_type").asText(""),
                    node.path("source").path("data").asText("")));
            // Forward compatibility: preserve unknown block types as raw text
            default -> new ContentBlock.Text(node.toString());
        };
    }

    static ObjectNode usageToJson(Usage usage) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("input_tokens", usage.inputTokens());
        node.put("output_tokens", usage.outputTokens());
        node.put("cache_creation_input_tokens", usage.cacheCreationInputTokens());
        node.put("cache_read_input_tokens", usage.cacheReadInputTokens());
        return node;
    }

    static Usage usageFromJson(JsonNode node) {
        if (node == null || node.isNull()) return Usage.ZERO;
        return new Usage(
                node.path("input_tokens").asInt(0),
                node.path("output_tokens").asInt(0),
                node.path("cache_creation_input_tokens").asInt(0),
                node.path("cache_read_input_tokens").asInt(0));
    }
}
