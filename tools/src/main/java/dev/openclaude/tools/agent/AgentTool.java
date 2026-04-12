package dev.openclaude.tools.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.openclaude.tools.*;

/**
 * Launches a sub-agent to handle complex tasks autonomously.
 * The sub-agent runs its own QueryEngine loop with an isolated context,
 * executes tools, and returns a summary result.
 *
 * This is a "meta-tool" — it doesn't do work directly, but delegates
 * to a new agent instance with its own conversation thread.
 */
public class AgentTool implements Tool {

    private static final JsonNode SCHEMA = SchemaBuilder.object()
            .stringProp("prompt", "The task for the agent to perform.", true)
            .stringProp("description", "Short (3-5 word) description of the task.", true)
            .build();

    private final AgentRunner runner;

    public AgentTool(AgentRunner runner) {
        this.runner = runner;
    }

    @Override
    public String name() {
        return "Agent";
    }

    @Override
    public String description() {
        return "Launch a new agent to handle complex, multi-step tasks autonomously.";
    }

    @Override
    public JsonNode inputSchema() {
        return SCHEMA;
    }

    @Override
    public ToolResult execute(JsonNode input, ToolUseContext context) {
        String prompt = input.path("prompt").asText("");
        String description = input.path("description").asText("");

        if (prompt.isBlank()) {
            return ToolResult.error("prompt is required.");
        }

        try {
            String result = runner.runAgent(prompt, context);
            return ToolResult.success(result);
        } catch (Exception e) {
            return ToolResult.error("Agent failed: " + e.getMessage());
        }
    }
}
