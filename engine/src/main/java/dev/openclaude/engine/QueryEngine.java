package dev.openclaude.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openclaude.core.hooks.HookDecision;
import dev.openclaude.core.hooks.HookExecutor;
import dev.openclaude.core.model.*;
import dev.openclaude.llm.LlmClient;
import dev.openclaude.llm.LlmRequest;
import dev.openclaude.tools.Tool;
import dev.openclaude.tools.ToolRegistry;
import dev.openclaude.tools.ToolResult;
import dev.openclaude.tools.ToolUseContext;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Core agent loop: sends messages to LLM, executes tool calls, feeds results back.
 * Repeats until the LLM responds with end_turn (no more tool calls).
 *
 * This is the heart of the coding agent — the equivalent of query.ts.
 */
public class QueryEngine {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_TOOL_LOOPS = 50; // Safety limit

    private final LlmClient client;
    private final ToolRegistry toolRegistry;
    private final String model;
    private final String systemPrompt;
    private final int maxTokens;
    private final Path workingDirectory;
    private final Consumer<EngineEvent> eventHandler;
    private final BackgroundAgentManager backgroundManager;
    private final HookExecutor hooks;
    private final Set<Path> readFiles = ConcurrentHashMap.newKeySet();

    public QueryEngine(
            LlmClient client,
            ToolRegistry toolRegistry,
            String model,
            String systemPrompt,
            int maxTokens,
            Path workingDirectory,
            Consumer<EngineEvent> eventHandler
    ) {
        this(client, toolRegistry, model, systemPrompt, maxTokens, workingDirectory, eventHandler, null, null);
    }

    public QueryEngine(
            LlmClient client,
            ToolRegistry toolRegistry,
            String model,
            String systemPrompt,
            int maxTokens,
            Path workingDirectory,
            Consumer<EngineEvent> eventHandler,
            BackgroundAgentManager backgroundManager
    ) {
        this(client, toolRegistry, model, systemPrompt, maxTokens, workingDirectory,
                eventHandler, backgroundManager, null);
    }

    public QueryEngine(
            LlmClient client,
            ToolRegistry toolRegistry,
            String model,
            String systemPrompt,
            int maxTokens,
            Path workingDirectory,
            Consumer<EngineEvent> eventHandler,
            BackgroundAgentManager backgroundManager,
            HookExecutor hooks
    ) {
        this.client = client;
        this.toolRegistry = toolRegistry;
        this.model = model;
        this.systemPrompt = systemPrompt;
        this.maxTokens = maxTokens;
        this.workingDirectory = workingDirectory;
        this.eventHandler = eventHandler;
        this.backgroundManager = backgroundManager;
        this.hooks = hooks;
    }

    /**
     * Run the agent loop for a user prompt.
     * Returns the final list of messages (full conversation).
     */
    public List<Message> run(String userPrompt) {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message.UserMessage(userPrompt));

        Usage totalUsage = Usage.ZERO;
        int loopCount = 0;

        while (loopCount < MAX_TOOL_LOOPS) {
            loopCount++;

            // Poll for completed background agents
            if (backgroundManager != null) {
                for (var completed : backgroundManager.pollCompleted()) {
                    eventHandler.accept(new EngineEvent.BackgroundAgentDone(
                            completed.description(), completed.result()));
                    String notification = "[Background agent completed: " + completed.description() + "]\n\n"
                            + completed.result();
                    messages.add(new Message.UserMessage(notification));
                }
            }

            // Build request with tools
            LlmRequest request = new LlmRequest(model, systemPrompt, messages, maxTokens)
                    .withTools(toolRegistry.toApiToolsArray());

            // Stream the response and collect the assistant message
            AssistantMessageBuilder builder = new AssistantMessageBuilder();

            client.streamMessage(request, event -> {
                // Forward stream events to the caller
                eventHandler.accept(new EngineEvent.Stream(event));

                // Also accumulate the message
                if (event instanceof StreamEvent.TextDelta td) {
                    builder.addText(td.text());
                } else if (event instanceof StreamEvent.ThinkingDelta th) {
                    builder.addThinking(th.thinking());
                } else if (event instanceof StreamEvent.ToolUseStart tus) {
                    builder.startToolUse(tus.id(), tus.name());
                } else if (event instanceof StreamEvent.ToolInputDelta tid) {
                    builder.appendToolInput(tid.partialJson());
                } else if (event instanceof StreamEvent.ContentBlockStop cbs) {
                    builder.finishContentBlock();
                } else if (event instanceof StreamEvent.MessageDelta md) {
                    builder.setStopReason(md.stopReason());
                    builder.setUsage(md.usage());
                } else if (event instanceof StreamEvent.Error err) {
                    builder.setError(err.message());
                }
            });

            // Check for errors
            if (builder.hasError()) {
                eventHandler.accept(new EngineEvent.Error(builder.getError()));
                break;
            }

            Message.AssistantMessage assistantMessage = builder.build();
            messages.add(assistantMessage);
            totalUsage = totalUsage.add(assistantMessage.usage() != null ? assistantMessage.usage() : Usage.ZERO);

            // Check if there are tool calls to execute
            List<ContentBlock.ToolUse> toolUses = extractToolUses(assistantMessage);

            if (toolUses.isEmpty()) {
                // No tool calls — the agent is done
                eventHandler.accept(new EngineEvent.Done(totalUsage, loopCount));
                break;
            }

            // Execute each tool call and build a user message with results
            List<ContentBlock> resultBlocks = new ArrayList<>();
            boolean stopRequested = false;
            for (ContentBlock.ToolUse toolUse : toolUses) {
                eventHandler.accept(new EngineEvent.ToolExecutionStart(toolUse.name(), toolUse.id()));

                JsonNode effectiveInput = toolUse.input();
                ToolResult result;

                HookDecision pre = hooks != null && !hooks.isEmpty()
                        ? hooks.runPreToolUse(toolUse.name(), effectiveInput)
                        : null;

                if (pre instanceof HookDecision.Stop stop) {
                    eventHandler.accept(new EngineEvent.ToolExecutionEnd(
                            toolUse.name(), toolUse.id(),
                            ToolResult.error("Hook stop: " + stop.stopReason())));
                    eventHandler.accept(new EngineEvent.Error(stop.stopReason()));
                    stopRequested = true;
                    break;
                }

                boolean toolRan;
                if (pre instanceof HookDecision.Deny deny) {
                    result = ToolResult.error("Blocked by hook: " + deny.reason());
                    toolRan = false;
                } else {
                    if (pre instanceof HookDecision.ReplaceInput r) {
                        effectiveInput = r.newInput();
                    }
                    result = executeTool(toolUse.name(), effectiveInput);
                    toolRan = true;
                }

                if (toolRan && hooks != null && !hooks.isEmpty()) {
                    HookDecision post = hooks.runPostToolUse(
                            toolUse.name(), effectiveInput, result.textContent(), result.isError());
                    if (post instanceof HookDecision.Stop stop) {
                        eventHandler.accept(new EngineEvent.ToolExecutionEnd(
                                toolUse.name(), toolUse.id(), result));
                        resultBlocks.add(new ContentBlock.ToolResult(
                                toolUse.id(), result.content(), result.isError()));
                        eventHandler.accept(new EngineEvent.Error(stop.stopReason()));
                        stopRequested = true;
                        break;
                    }
                    if (post instanceof HookDecision.Allow a && a.additionalContext() != null) {
                        List<ContentBlock> augmented = new ArrayList<>(result.content());
                        augmented.add(new ContentBlock.Text(
                                "\n[hook additionalContext]\n" + a.additionalContext()));
                        result = new ToolResult(augmented, result.isError());
                    }
                }

                eventHandler.accept(new EngineEvent.ToolExecutionEnd(
                        toolUse.name(), toolUse.id(), result));

                resultBlocks.add(new ContentBlock.ToolResult(
                        toolUse.id(), result.content(), result.isError()));
            }

            // Add tool results as a user message and loop
            messages.add(new Message.UserMessage(resultBlocks));

            if (stopRequested) break;
        }

        if (loopCount >= MAX_TOOL_LOOPS) {
            eventHandler.accept(new EngineEvent.Error(
                    "Agent loop reached maximum of " + MAX_TOOL_LOOPS + " iterations."));
        }

        return messages;
    }

    private List<ContentBlock.ToolUse> extractToolUses(Message.AssistantMessage message) {
        List<ContentBlock.ToolUse> toolUses = new ArrayList<>();
        for (ContentBlock block : message.content()) {
            if (block instanceof ContentBlock.ToolUse tu) {
                toolUses.add(tu);
            }
        }
        return toolUses;
    }

    private ToolResult executeTool(String toolName, JsonNode toolInput) {
        var toolOpt = toolRegistry.findByName(toolName);
        if (toolOpt.isEmpty()) {
            return ToolResult.error("Unknown tool: " + toolName);
        }

        Tool tool = toolOpt.get();
        ToolUseContext context = new ToolUseContext(workingDirectory, false, readFiles);

        try {
            return tool.execute(toolInput, context);
        } catch (Exception e) {
            return ToolResult.error("Tool execution failed: " + e.getMessage());
        }
    }
}
