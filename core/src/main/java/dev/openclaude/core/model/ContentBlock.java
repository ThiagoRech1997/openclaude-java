package dev.openclaude.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Content blocks within messages, matching the Anthropic Messages API format.
 */
public sealed interface ContentBlock {

    record Text(
            @JsonProperty("type") String type,
            @JsonProperty("text") String text
    ) implements ContentBlock {
        public Text(String text) {
            this("text", text);
        }
    }

    record Thinking(
            @JsonProperty("type") String type,
            @JsonProperty("thinking") String thinking
    ) implements ContentBlock {
        public Thinking(String thinking) {
            this("thinking", thinking);
        }
    }

    record ToolUse(
            @JsonProperty("type") String type,
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("input") JsonNode input
    ) implements ContentBlock {
        public ToolUse(String id, String name, JsonNode input) {
            this("tool_use", id, name, input);
        }
    }

    record ToolResult(
            @JsonProperty("type") String type,
            @JsonProperty("tool_use_id") String toolUseId,
            @JsonProperty("content") String content,
            @JsonProperty("is_error") boolean isError
    ) implements ContentBlock {
        public ToolResult(String toolUseId, String content, boolean isError) {
            this("tool_result", toolUseId, content, isError);
        }
    }
}
