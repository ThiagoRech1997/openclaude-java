package dev.openclaude.tools;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Core tool interface — every tool implements this contract.
 * Simplified from the TypeScript version while keeping essential behavior.
 */
public interface Tool {

    /** Unique tool name sent to the LLM (e.g., "Bash", "Read", "Edit"). */
    String name();

    /** Human-readable description of what this tool does. */
    String description();

    /** JSON Schema for the tool's input parameters. */
    JsonNode inputSchema();

    /** Whether the tool is read-only (doesn't modify state). */
    default boolean isReadOnly() {
        return false;
    }

    /** Whether this tool is currently enabled. */
    default boolean isEnabled() {
        return true;
    }

    /**
     * Execute the tool with the given input.
     *
     * @param input the tool input as parsed JSON
     * @param context execution context (working directory, abort signal, etc.)
     * @return the tool result
     */
    ToolResult execute(JsonNode input, ToolUseContext context);
}
