package dev.openclaude.engine;

import dev.openclaude.core.config.ModelAlias;
import dev.openclaude.core.model.*;
import dev.openclaude.core.permissions.PermissionManager;
import dev.openclaude.engine.agents.SubAgentDefinition;
import dev.openclaude.engine.agents.SubAgentRegistry;
import dev.openclaude.llm.LlmClient;
import dev.openclaude.tools.ToolRegistry;
import dev.openclaude.tools.ToolUseContext;
import dev.openclaude.tools.agent.AgentRunRequest;
import dev.openclaude.tools.agent.AgentRunner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Runs a sub-agent by creating an isolated QueryEngine instance.
 * Resolves system prompt, tool whitelist, and model override from a {@link SubAgentRegistry};
 * supports background execution and worktree isolation.
 */
public class SubAgentRunner implements AgentRunner {

    private final LlmClient client;
    private final ToolRegistry toolRegistry;
    private final String model;
    private final int maxTokens;
    private final BackgroundAgentManager backgroundManager;
    private final PermissionManager permissions;
    private final SubAgentRegistry subAgentRegistry;

    public SubAgentRunner(LlmClient client, ToolRegistry toolRegistry, String model, int maxTokens,
                          BackgroundAgentManager backgroundManager, PermissionManager permissions,
                          SubAgentRegistry subAgentRegistry) {
        this.client = client;
        this.toolRegistry = toolRegistry;
        this.model = model;
        this.maxTokens = maxTokens;
        this.backgroundManager = backgroundManager;
        this.permissions = permissions;
        this.subAgentRegistry = subAgentRegistry;
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
        SubAgentDefinition def = subAgentRegistry.get(request.subagentType());
        if (def == null) def = subAgentRegistry.fallback();

        String systemPrompt = def.systemPrompt();
        String modelChoice = request.model() != null ? request.model() : def.modelOverride();
        String resolvedModel = ModelAlias.resolve(modelChoice, this.model);
        ToolRegistry filteredTools = resolveToolRegistry(def);

        Path workDir = parentContext.workingDirectory();
        WorktreeSession session = null;
        if ("worktree".equals(request.isolation())) {
            try {
                session = WorktreeSession.create(workDir);
                workDir = session.path();
            } catch (IOException e) {
                return "Failed to create worktree: " + e.getMessage();
            }
        }

        StringBuilder resultText = new StringBuilder();
        boolean runFailed = false;
        try {
            QueryEngine engine = new QueryEngine(
                    client, filteredTools, resolvedModel, systemPrompt,
                    maxTokens, workDir,
                    event -> {
                        if (event instanceof EngineEvent.Stream s) {
                            if (s.event() instanceof StreamEvent.TextDelta td) {
                                resultText.append(td.text());
                            }
                        }
                    }, null, null, permissions, null
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
        } catch (RuntimeException e) {
            runFailed = true;
            throw e;
        } finally {
            if (session != null) {
                WorktreeSession.Result r = session.finishAndMaybeCleanup(runFailed);
                if (r.kept()) {
                    resultText.append("\n\n---\nWorktree: ").append(r.path())
                            .append("\nBranch: ").append(r.branch()).append("\n");
                }
            }
        }

        return resultText.length() > 0 ? resultText.toString() : "(Agent completed with no text output)";
    }

    private ToolRegistry resolveToolRegistry(SubAgentDefinition def) {
        if (def.toolFilter() != null) {
            return toolRegistry.filteredCopy(def.toolFilter());
        }
        if (def.toolWhitelist() != null && !def.toolWhitelist().isEmpty()) {
            Set<String> allowed = Set.copyOf(def.toolWhitelist());
            return toolRegistry.filteredCopy(t -> allowed.contains(t.name()) && !t.name().equals("Agent"));
        }
        return toolRegistry.filteredCopy(t -> !t.name().equals("Agent"));
    }
}
