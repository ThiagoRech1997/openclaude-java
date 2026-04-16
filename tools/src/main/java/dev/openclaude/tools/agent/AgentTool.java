package dev.openclaude.tools.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.openclaude.tools.*;

/**
 * Launches a sub-agent to handle complex tasks autonomously.
 * The sub-agent runs its own QueryEngine loop with an isolated context,
 * executes tools, and returns a summary result.
 *
 * Supports typed sub-agents (Explore, Plan), model override, and background execution.
 */
public class AgentTool implements Tool {

    private static final JsonNode SCHEMA = SchemaBuilder.object()
            .stringProp("prompt", "The task for the agent to perform.", true)
            .stringProp("description", "Short (3-5 word) description of the task.", true)
            .enumProp("subagent_type",
                    "The type of sub-agent to launch. Explore is optimized for search and reading (read-only tools). "
                            + "Plan is optimized for designing implementation plans (no file editing).",
                    false, "general-purpose", "Explore", "Plan")
            .enumProp("model",
                    "Optional model override. Allows the sub-agent to use a different model than the parent.",
                    false, "sonnet", "opus", "haiku")
            .boolProp("run_in_background",
                    "Run the sub-agent in a background thread. Returns immediately and notifies when complete.",
                    false)
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

        String subagentType = input.has("subagent_type") ? input.get("subagent_type").asText() : null;
        String model = input.has("model") ? input.get("model").asText() : null;
        boolean runInBackground = input.path("run_in_background").asBoolean(false);

        AgentRunRequest request = new AgentRunRequest(prompt, description, subagentType, model, runInBackground);

        try {
            String result = runner.runAgent(request, context);
            return ToolResult.success(result);
        } catch (Exception e) {
            return ToolResult.error("Agent failed: " + e.getMessage());
        }
    }
}
