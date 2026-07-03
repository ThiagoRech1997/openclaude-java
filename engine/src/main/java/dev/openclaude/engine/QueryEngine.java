package dev.openclaude.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openclaude.core.hooks.HookDecision;
import dev.openclaude.core.hooks.HookExecutor;
import dev.openclaude.core.model.*;
import dev.openclaude.core.permissions.PermissionManager;
import dev.openclaude.core.permissions.PermissionMode;
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

    /** Appended to the system prompt while the session is in plan mode. */
    static final String PLAN_MODE_PROMPT = """
            == PLAN MODE ==
            You are in plan mode. Do NOT make any changes: no file writes or edits, \
            no state-changing commands, and do not output implementation code as the answer. \
            Use read-only tools (Read, Grep, Glob, WebFetch, ...) to research as needed.
            Produce a concrete implementation plan: numbered steps, files affected, and \
            trade-offs considered. When the plan is complete, call the ExitPlanMode tool \
            with the full plan in markdown and wait for the user's approval. \
            Do not begin implementing until the user approves the plan.""";

    private final LlmClient client;
    private final ToolRegistry toolRegistry;
    private final String model;
    private final String systemPrompt;
    private final int maxTokens;
    private final Path workingDirectory;
    private final Consumer<EngineEvent> eventHandler;
    private final BackgroundAgentManager backgroundManager;
    private final HookExecutor hooks;
    private final PermissionManager permissions;
    private final PermissionHandler permissionHandler;
    private final Set<Path> readFiles = ConcurrentHashMap.newKeySet();
    /** Shared across tool executions so EnterWorktree can move the session's cwd. */
    private final dev.openclaude.tools.WorkspaceState workspace;
    private volatile boolean abortRequested = false;

    /** Thrown from the stream handler to unwind out of the LLM client on abort. */
    private static final class AbortException extends RuntimeException {}

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
        this(client, toolRegistry, model, systemPrompt, maxTokens, workingDirectory,
                eventHandler, backgroundManager, hooks, null, null);
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
            HookExecutor hooks,
            PermissionManager permissions,
            PermissionHandler permissionHandler
    ) {
        this.client = client;
        this.toolRegistry = toolRegistry;
        this.model = model;
        this.systemPrompt = systemPrompt;
        this.maxTokens = maxTokens;
        this.workingDirectory = workingDirectory;
        this.workspace = new dev.openclaude.tools.WorkspaceState(workingDirectory);
        this.eventHandler = eventHandler;
        this.backgroundManager = backgroundManager;
        this.hooks = hooks;
        this.permissions = permissions;
        this.permissionHandler = permissionHandler;
    }

    /**
     * Run the agent loop for a user prompt, starting a fresh conversation.
     * Returns the final list of messages (full conversation).
     */
    public List<Message> run(String userPrompt) {
        return run(List.of(), userPrompt);
    }

    /**
     * Run the agent loop for a user prompt, continuing from prior conversation
     * history. The history is copied, never mutated; the returned list is the
     * full updated conversation including the new turn.
     */
    public List<Message> run(List<Message> history, String userPrompt) {
        List<Message> messages = new ArrayList<>(history);
        messages.add(new Message.UserMessage(userPrompt));

        abortRequested = false;
        Usage totalUsage = Usage.ZERO;
        int loopCount = 0;
        boolean exhausted = true;

        while (loopCount < MAX_TOOL_LOOPS) {
            loopCount++;

            if (abortRequested) {
                eventHandler.accept(new EngineEvent.Aborted());
                exhausted = false;
                break;
            }

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

            // Build request with tools. The plan-mode prompt is resolved every
            // iteration so ExitPlanMode approval mid-run takes effect immediately.
            String effectiveSystemPrompt = systemPrompt;
            if (permissions != null && permissions.getMode() == PermissionMode.PLAN) {
                effectiveSystemPrompt = (systemPrompt == null || systemPrompt.isBlank())
                        ? PLAN_MODE_PROMPT
                        : systemPrompt + "\n\n" + PLAN_MODE_PROMPT;
            }
            LlmRequest request = new LlmRequest(model, effectiveSystemPrompt, messages, maxTokens)
                    .withTools(toolRegistry.toApiToolsArray());

            // Stream the response and collect the assistant message
            AssistantMessageBuilder builder = new AssistantMessageBuilder();

            boolean streamAborted = false;
            try {
                client.streamMessage(request, event -> {
                    if (abortRequested) {
                        throw new AbortException();
                    }
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
            } catch (AbortException e) {
                streamAborted = true;
            }

            // On abort the partial assistant message is dropped — the history
            // still ends on the prior user message, which the API accepts
            if (streamAborted || (builder.hasError() && abortRequested)) {
                eventHandler.accept(new EngineEvent.Aborted());
                exhausted = false;
                break;
            }

            // Check for errors
            if (builder.hasError()) {
                eventHandler.accept(new EngineEvent.Error(builder.getError()));
                exhausted = false;
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
                exhausted = false;
                break;
            }

            // Execute each tool call and build a user message with results
            List<ContentBlock> resultBlocks = new ArrayList<>();
            boolean stopRequested = false;
            boolean abortedTurn = false;
            String skipReason = "Skipped: a hook requested stop.";
            for (ContentBlock.ToolUse toolUse : toolUses) {
                if (abortRequested) {
                    skipReason = "Interrupted by user.";
                    stopRequested = true;
                    abortedTurn = true;
                    break;
                }

                JsonNode effectiveInput = toolUse.input();
                ToolResult result;

                HookDecision pre = hooks != null && !hooks.isEmpty()
                        ? hooks.runPreToolUse(toolUse.name(), effectiveInput)
                        : null;

                if (pre instanceof HookDecision.Stop stop) {
                    ToolResult stopResult = ToolResult.error("Hook stop: " + stop.stopReason());
                    eventHandler.accept(new EngineEvent.ToolExecutionStart(toolUse.name(), toolUse.id()));
                    eventHandler.accept(new EngineEvent.ToolExecutionEnd(
                            toolUse.name(), toolUse.id(), stopResult));
                    resultBlocks.add(new ContentBlock.ToolResult(
                            toolUse.id(), stopResult.content(), true));
                    eventHandler.accept(new EngineEvent.Error(stop.stopReason()));
                    stopRequested = true;
                    break;
                }

                boolean toolRan;
                if (pre instanceof HookDecision.Deny deny) {
                    eventHandler.accept(new EngineEvent.ToolExecutionStart(toolUse.name(), toolUse.id()));
                    result = ToolResult.error("Blocked by hook: " + deny.reason());
                    toolRan = false;
                } else {
                    if (pre instanceof HookDecision.ReplaceInput r) {
                        effectiveInput = r.newInput();
                    }
                    // Resolve permission BEFORE announcing execution — the interactive
                    // ASK prompt must not render under a "Running ..." spinner
                    PermissionManager.PermissionDecision permDecision =
                            checkPermission(toolUse.name(), effectiveInput);
                    eventHandler.accept(new EngineEvent.ToolExecutionStart(toolUse.name(), toolUse.id()));
                    if (permDecision == PermissionManager.PermissionDecision.DENIED) {
                        if (permissions != null) permissions.recordDenial(toolUse.name());
                        result = ToolResult.error("Denied by permission policy: " + toolUse.name());
                        toolRan = false;
                    } else {
                        result = executeTool(toolUse.name(), effectiveInput);
                        toolRan = true;
                    }
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

            // Every tool_use needs a matching tool_result — the API rejects
            // the conversation otherwise
            if (stopRequested && resultBlocks.size() < toolUses.size()) {
                for (int i = resultBlocks.size(); i < toolUses.size(); i++) {
                    ContentBlock.ToolUse skipped = toolUses.get(i);
                    resultBlocks.add(new ContentBlock.ToolResult(
                            skipped.id(),
                            List.of(new ContentBlock.Text(skipReason)),
                            true));
                }
            }

            // Add tool results as a user message and loop
            messages.add(new Message.UserMessage(resultBlocks));

            if (stopRequested) {
                if (abortedTurn) {
                    eventHandler.accept(new EngineEvent.Aborted());
                }
                exhausted = false;
                break;
            }
        }

        if (exhausted) {
            eventHandler.accept(new EngineEvent.Error(
                    "Agent loop reached maximum of " + MAX_TOOL_LOOPS + " iterations."));
        }

        return messages;
    }

    /**
     * Request cancellation of the in-flight run. Safe to call from any thread
     * (e.g. a SIGINT handler); the loop stops at the next checkpoint and the
     * returned history stays API-valid (every tool_use keeps a tool_result).
     */
    public void requestAbort() {
        abortRequested = true;
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

    private PermissionManager.PermissionDecision checkPermission(String toolName, JsonNode input) {
        if (permissions == null) {
            return PermissionManager.PermissionDecision.ALLOWED;
        }
        boolean isReadOnly = toolRegistry.findByName(toolName)
                .map(Tool::isReadOnly)
                .orElse(false);
        PermissionManager.PermissionDecision decision = permissions.check(toolName, isReadOnly);
        if (decision != PermissionManager.PermissionDecision.ASK) {
            return decision;
        }
        if (permissionHandler == null) {
            return PermissionManager.PermissionDecision.DENIED;
        }
        PermissionManager.PermissionDecision resolved = permissionHandler.ask(toolName, input, isReadOnly);
        return resolved == PermissionManager.PermissionDecision.ALLOWED
                ? PermissionManager.PermissionDecision.ALLOWED
                : PermissionManager.PermissionDecision.DENIED;
    }

    private ToolResult executeTool(String toolName, JsonNode toolInput) {
        var toolOpt = toolRegistry.findByName(toolName);
        if (toolOpt.isEmpty()) {
            return ToolResult.error("Unknown tool: " + toolName);
        }

        Tool tool = toolOpt.get();
        ToolUseContext context = new ToolUseContext(workspace, false, readFiles);

        try {
            return tool.execute(toolInput, context);
        } catch (Exception e) {
            return ToolResult.error("Tool execution failed: " + e.getMessage());
        }
    }
}
