package dev.openclaude.tools.agent;

import dev.openclaude.tools.ToolUseContext;

/**
 * Interface for running sub-agents.
 * Implemented by the engine module to avoid circular dependencies.
 */
@FunctionalInterface
public interface AgentRunner {

    /**
     * Run a sub-agent with the given prompt in an isolated context.
     *
     * @param prompt the task for the agent
     * @param parentContext the parent's execution context
     * @return the agent's final text response
     */
    String runAgent(String prompt, ToolUseContext parentContext);
}
