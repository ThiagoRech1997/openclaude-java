package dev.openclaude.tools;

import dev.openclaude.core.model.ContentBlock;

import java.util.List;

/**
 * Result from a tool execution.
 * Content is a list of ContentBlocks — typically a single Text block, but may include
 * Image blocks (e.g. from FileReadTool when reading images or notebook image outputs).
 */
public record ToolResult(
        List<ContentBlock> content,
        boolean isError
) {
    public static ToolResult success(String content) {
        return new ToolResult(List.of(new ContentBlock.Text(content)), false);
    }

    public static ToolResult success(List<ContentBlock> blocks) {
        return new ToolResult(List.copyOf(blocks), false);
    }

    public static ToolResult error(String message) {
        return new ToolResult(List.of(new ContentBlock.Text(message)), true);
    }

    /** Concatenates all inner Text blocks into a single string. */
    public String textContent() {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : content) {
            if (b instanceof ContentBlock.Text t) sb.append(t.text());
        }
        return sb.toString();
    }
}
