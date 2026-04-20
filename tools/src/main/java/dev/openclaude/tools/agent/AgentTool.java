package dev.openclaude.tools.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.openclaude.tools.*;

import java.util.List;

/**
 * Launches a sub-agent to handle complex tasks autonomously.
 * The sub-agent runs its own QueryEngine loop with an isolated context,
 * executes tools, and returns a summary result.
 *
 * Supports typed sub-agents (including custom markdown-defined ones), model override,
 * background execution, and optional worktree isolation.
 */
public class AgentTool implements Tool {

    private final AgentRunner runner;
    private final JsonNode schema;

    /**
     * @param runner         executes sub-agent invocations
     * @param subAgentNames  names of all known sub-agent types, injected into the
     *                       {@code subagent_type} description so the LLM can pick from the
     *                       current registry (built-ins + markdown-loaded).
     */
    public AgentTool(AgentRunner runner, List<String> subAgentNames) {
        this.runner = runner;
        this.schema = buildSchema(subAgentNames);
    }

    private static JsonNode buildSchema(List<String> subAgentNames) {
        String available = subAgentNames == null || subAgentNames.isEmpty()
                ? "general-purpose"
                : String.join(", ", subAgentNames);
        String typeDesc = "The type of sub-agent to launch. Available types: " + available + ". "
                + "Explore is optimized for search and reading (read-only tools). "
                + "Plan is optimized for designing implementation plans (no file editing). "
                + "general-purpose has full tool access.";
        return SchemaBuilder.object()
                .stringProp("prompt", "The task for the agent to perform.", true)
                .stringProp("description", "Short (3-5 word) description of the task.", true)
                .stringProp("subagent_type", typeDesc, false)
                .enumProp("model",
                        "Optional model override. Allows the sub-agent to use a different model than the parent.",
                        false, "sonnet", "opus", "haiku")
                .boolProp("run_in_background",
                        "Run the sub-agent in a background thread. Returns immediately and notifies when complete.",
                        false)
                .enumProp("isolation",
                        "Isolation mode. With \"worktree\", the sub-agent runs in a temporary git worktree on its own "
                                + "branch; the worktree is automatically cleaned up if the agent makes no changes, "
                                + "otherwise the path and branch are returned in the result. The worktree starts from "
                                + "HEAD, so uncommitted changes in the parent are not visible to the sub-agent.",
                        false, "worktree")
                .build();
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
        return schema;
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

        String isolation = input.has("isolation") ? input.get("isolation").asText() : null;
        if (isolation != null && !isolation.equals("worktree")) {
            return ToolResult.error("isolation must be 'worktree' or omitted.");
        }

        AgentRunRequest request = new AgentRunRequest(prompt, description, subagentType, model, runInBackground, isolation);

        try {
            String result = runner.runAgent(request, context);
            return ToolResult.success(result);
        } catch (Exception e) {
            return ToolResult.error("Agent failed: " + e.getMessage());
        }
    }
}
