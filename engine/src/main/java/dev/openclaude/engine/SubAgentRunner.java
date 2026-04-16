package dev.openclaude.engine;

import dev.openclaude.core.config.ModelAlias;
import dev.openclaude.core.model.*;
import dev.openclaude.llm.LlmClient;
import dev.openclaude.tools.ToolRegistry;
import dev.openclaude.tools.ToolUseContext;
import dev.openclaude.tools.agent.AgentRunRequest;
import dev.openclaude.tools.agent.AgentRunner;

import java.util.List;

/**
 * Runs a sub-agent by creating an isolated QueryEngine instance.
 * Supports typed sub-agents (Explore, Plan), model override, and background execution.
 */
public class SubAgentRunner implements AgentRunner {

    private static final String GENERAL_PURPOSE_PROMPT =
            "You are a sub-agent launched to handle a specific task. "
                    + "Complete the task thoroughly and return a concise summary of what you did and found. "
                    + "You have access to the same tools as the parent agent.";

    private static final String EXPLORE_PROMPT =
            "You are an exploration sub-agent optimized for searching and reading code. "
                    + "You have read-only access — do NOT attempt to create, modify, or delete any files. "
                    + "Search, read, and analyze the codebase, then return a concise summary of your findings.";

    private static final String PLAN_PROMPT =
            "You are a planning sub-agent. Your job is to explore the codebase and design implementation plans. "
                    + "You must NOT create, modify, or delete files. "
                    + "Analyze the codebase and return a detailed, actionable implementation plan.";

    private final LlmClient client;
    private final ToolRegistry toolRegistry;
    private final String model;
    private final int maxTokens;
    private final BackgroundAgentManager backgroundManager;

    public SubAgentRunner(LlmClient client, ToolRegistry toolRegistry, String model, int maxTokens,
                          BackgroundAgentManager backgroundManager) {
        this.client = client;
        this.toolRegistry = toolRegistry;
        this.model = model;
        this.maxTokens = maxTokens;
        this.backgroundManager = backgroundManager;
    }

    @Override
    public String runAgent(AgentRunRequest request, ToolUseContext parentContext) {
        if (request.runInBackground()) {
            backgroundManager.submit(request.description(), () -> executeAgent(request, parentContext));
            return "Agent started in background: " + request.description()
                    + ". You will be notified when it completes.";
        }
        return executeAgent(request, parentContext);
    }

    private String executeAgent(AgentRunRequest request, ToolUseContext parentContext) {
        String systemPrompt = resolveSystemPrompt(request.subagentType());
        String resolvedModel = ModelAlias.resolve(request.model(), this.model);
        ToolRegistry filteredTools = resolveToolRegistry(request.subagentType());

        StringBuilder resultText = new StringBuilder();

        QueryEngine engine = new QueryEngine(
                client, filteredTools, resolvedModel, systemPrompt,
                maxTokens, parentContext.workingDirectory(),
                event -> {
                    if (event instanceof EngineEvent.Stream s) {
                        if (s.event() instanceof StreamEvent.TextDelta td) {
                            resultText.append(td.text());
                        }
                    }
                }
        );

        List<Message> messages = engine.run(request.prompt());

        // Extract the final assistant text
        if (resultText.length() == 0) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (messages.get(i) instanceof Message.AssistantMessage am) {
                    for (ContentBlock block : am.content()) {
                        if (block instanceof ContentBlock.Text t) {
                            resultText.append(t.text());
                        }
                    }
                    break;
                }
            }
        }

        return resultText.length() > 0 ? resultText.toString() : "(Agent completed with no text output)";
    }

    private String resolveSystemPrompt(String subagentType) {
        if (subagentType == null) return GENERAL_PURPOSE_PROMPT;
        return switch (subagentType) {
            case "Explore" -> EXPLORE_PROMPT;
            case "Plan" -> PLAN_PROMPT;
            default -> GENERAL_PURPOSE_PROMPT;
        };
    }

    private ToolRegistry resolveToolRegistry(String subagentType) {
        if (subagentType == null) {
            return toolRegistry.filteredCopy(t -> !t.name().equals("Agent"));
        }
        return switch (subagentType) {
            case "Explore" -> toolRegistry.filteredCopy(
                    t -> (t.isReadOnly() || t.name().equals("Bash")) && !t.name().equals("Agent"));
            case "Plan" -> toolRegistry.filteredCopy(
                    t -> !t.name().equals("FileWrite") && !t.name().equals("FileEdit")
                            && !t.name().equals("Agent"));
            default -> toolRegistry.filteredCopy(t -> !t.name().equals("Agent"));
        };
    }
}
