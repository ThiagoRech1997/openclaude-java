package dev.openclaude.tools.agent;

import dev.openclaude.tools.ToolUseContext;

/**
 * Interface for running sub-agents.
 * Implemented by the engine module to avoid circular dependencies.
 */
@FunctionalInterface
public interface AgentRunner {

    /**
     * Run a sub-agent with the given request in an isolated context.
     *
     * @param request       the agent run parameters (prompt, type, model, etc.)
     * @param parentContext the parent's execution context
     * @return the agent's final text response
     */
    String runAgent(AgentRunRequest request, ToolUseContext parentContext);
}
