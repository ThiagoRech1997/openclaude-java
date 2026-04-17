package dev.openclaude.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

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

    record Image(
            @JsonProperty("type") String type,
            @JsonProperty("source") Source source
    ) implements ContentBlock {
        public Image(Source source) {
            this("image", source);
        }

        public Image(String mediaType, String base64Data) {
            this("image", new Source("base64", mediaType, base64Data));
        }

        public record Source(
                @JsonProperty("type") String type,
                @JsonProperty("media_type") String mediaType,
                @JsonProperty("data") String data
        ) {}
    }

    record ToolResult(
            @JsonProperty("type") String type,
            @JsonProperty("tool_use_id") String toolUseId,
            @JsonProperty("content") List<ContentBlock> content,
            @JsonProperty("is_error") boolean isError
    ) implements ContentBlock {
        public ToolResult(String toolUseId, List<ContentBlock> content, boolean isError) {
            this("tool_result", toolUseId, content, isError);
        }

        public ToolResult(String toolUseId, String text, boolean isError) {
            this("tool_result", toolUseId, List.of(new Text(text)), isError);
        }

        /** Concatenates all inner Text blocks into a single string. */
        public String textContent() {
            StringBuilder sb = new StringBuilder();
            for (ContentBlock b : content) {
                if (b instanceof Text t) sb.append(t.text());
            }
            return sb.toString();
        }
    }
}
