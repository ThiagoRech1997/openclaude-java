package dev.openclaude.tools;

/**
 * Result from a tool execution.
 */
public record ToolResult(
        String content,
        boolean isError
) {
    public static ToolResult success(String content) {
        return new ToolResult(content, false);
    }

    public static ToolResult error(String message) {
        return new ToolResult(message, true);
    }
}
