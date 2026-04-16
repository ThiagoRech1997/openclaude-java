package dev.openclaude.tools.agent;

/**
 * Parameters for a sub-agent invocation.
 *
 * @param prompt         the task for the agent to perform
 * @param description    short description of the task
 * @param subagentType   type of sub-agent ("general-purpose", "Explore", "Plan") — null defaults to general-purpose
 * @param model          model alias ("sonnet", "opus", "haiku") — null uses parent model
 * @param runInBackground whether to run the agent asynchronously
 */
public record AgentRunRequest(
        String prompt,
        String description,
        String subagentType,
        String model,
        boolean runInBackground
) {}
