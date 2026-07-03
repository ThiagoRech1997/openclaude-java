package dev.openclaude.tui;

import dev.openclaude.commands.*;
import dev.openclaude.core.config.AppConfig;
import dev.openclaude.core.hooks.HookDecision;
import dev.openclaude.core.hooks.HookExecutor;
import dev.openclaude.core.model.Message;
import dev.openclaude.core.permissions.PermissionManager;
import dev.openclaude.core.session.SessionManager;
import dev.openclaude.engine.BackgroundAgentManager;
import dev.openclaude.engine.QueryEngine;
import dev.openclaude.llm.LlmClient;
import dev.openclaude.tools.ToolRegistry;
import dev.openclaude.tools.plan.ExitPlanModeTool;
import dev.openclaude.tui.render.MarkdownRenderer;
import dev.openclaude.tui.widget.AgentDisplay;
import dev.openclaude.tui.widget.TextInput;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Interactive REPL (Read-Eval-Print Loop) for the coding agent.
 * Now integrated with CommandRegistry, PermissionManager, and SessionManager.
 */
public class Repl {

    private final AppConfig config;
    private final LlmClient client;
    private final ToolRegistry toolRegistry;
    private final Path workingDirectory;
    private final String systemPrompt;
    private final CommandRegistry commandRegistry;
    private final PermissionManager permissions;
    private final SessionManager session;
    private final BackgroundAgentManager backgroundManager;
    private final HookExecutor hooks;
    private boolean saveWarned = false;

    public Repl(AppConfig config, LlmClient client, ToolRegistry toolRegistry,
                Path workingDirectory, String systemPrompt,
                CommandRegistry commandRegistry, PermissionManager permissions,
                BackgroundAgentManager backgroundManager) {
        this(config, client, toolRegistry, workingDirectory, systemPrompt,
                commandRegistry, permissions, backgroundManager, null);
    }

    public Repl(AppConfig config, LlmClient client, ToolRegistry toolRegistry,
                Path workingDirectory, String systemPrompt,
                CommandRegistry commandRegistry, PermissionManager permissions,
                BackgroundAgentManager backgroundManager, HookExecutor hooks) {
        this(config, client, toolRegistry, workingDirectory, systemPrompt,
                commandRegistry, permissions, backgroundManager, hooks, new SessionManager());
    }

    public Repl(AppConfig config, LlmClient client, ToolRegistry toolRegistry,
                Path workingDirectory, String systemPrompt,
                CommandRegistry commandRegistry, PermissionManager permissions,
                BackgroundAgentManager backgroundManager, HookExecutor hooks,
                SessionManager session) {
        this.config = config;
        this.client = client;
        this.toolRegistry = toolRegistry;
        this.workingDirectory = workingDirectory;
        this.systemPrompt = systemPrompt;
        this.commandRegistry = commandRegistry;
        this.permissions = permissions;
        this.session = session;
        this.backgroundManager = backgroundManager;
        this.hooks = hooks;
    }

    /**
     * Start the interactive REPL loop.
     */
    public void start() {
        try (TerminalScreen screen = new TerminalScreen()) {
            AgentDisplay display = new AgentDisplay(screen);
            TextInput input = new TextInput(screen.getTerminal(), promptString());

            display.showWelcome(config.provider(), config.model(), toolRegistry.size());
            if (!session.getMessages().isEmpty()) {
                screen.println(Ansi.DIM + "  Resumed session " + session.getSessionId()
                        + " (" + session.getMessages().size() + " messages)" + Ansi.RESET);
                screen.println();
            }

            CommandContext cmdCtx = new CommandContext(
                    config, toolRegistry, permissions, session,
                    workingDirectory, screen.getWidth(), commandRegistry);

            ReplPermissionHandler permissionHandler = new ReplPermissionHandler(screen, permissions);

            // Wire the plan-approval UI into the ExitPlanMode tool, if registered
            toolRegistry.findByName("ExitPlanMode").ifPresent(tool -> {
                if (tool instanceof ExitPlanModeTool exitPlanMode) {
                    exitPlanMode.setApprovalHandler(plan -> approvePlan(plan, screen));
                }
            });

            // Factory so custom commands with allowed-tools can run one turn on a
            // restricted registry without touching the session engine.
            Function<ToolRegistry, QueryEngine> engineFactory = registry -> new QueryEngine(
                    client, registry, config.model(), systemPrompt,
                    config.maxTokens(), workingDirectory, event -> {
                display.handleEvent(event);
                if (event instanceof dev.openclaude.engine.EngineEvent.Done done) {
                    session.addUsage(done.totalUsage());
                }
            }, backgroundManager, hooks, permissions, permissionHandler);

            // One engine for the whole session so per-session tool state
            // (e.g. files read, required by FileWriteTool) survives across turns.
            QueryEngine engine = engineFactory.apply(toolRegistry);

            while (true) {
                String userInput = input.readLineSafe();

                if (userInput == null) {
                    screen.println();
                    screen.println(Ansi.DIM + "  Goodbye!" + Ansi.RESET);
                    screen.println();
                    break;
                }

                String trimmed = userInput.trim();
                if (trimmed.isEmpty()) continue;

                // A throwing command or renderer must not kill the whole session
                try {
                    // Handle slash commands
                    if (trimmed.startsWith("/")) {
                        CommandResult result = commandRegistry.dispatch(trimmed, cmdCtx);
                        if (result != null) {
                            if (!handleCommandResult(result, screen, engine, engineFactory)) break;
                            continue;
                        }
                    }

                    if (!runAgentTurn(trimmed, engine, screen)) break;
                } catch (RuntimeException e) {
                    screen.println();
                    screen.println(Ansi.RED + "  Error: " + e + Ansi.RESET);
                    screen.println();
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to initialize terminal: " + e.getMessage());
        }
    }

    /**
     * Run one agent turn on {@code prompt}. Returns false if the hook pipeline asked the REPL to stop.
     */
    private boolean runAgentTurn(String prompt, QueryEngine engine, TerminalScreen screen) {
        screen.println();
        session.incrementTurn();

        String effectivePrompt = prompt;
        if (hooks != null && !hooks.isEmpty()) {
            HookDecision pre = hooks.runUserPromptSubmit(prompt);
            if (pre instanceof HookDecision.Deny deny) {
                screen.println(Ansi.RED + "  [hook blocked prompt] " + deny.reason() + Ansi.RESET);
                screen.println();
                return true;
            }
            if (pre instanceof HookDecision.Stop stop) {
                screen.println(Ansi.RED + "  [hook stop] " + stop.stopReason() + Ansi.RESET);
                screen.println();
                return false;
            }
            if (pre instanceof HookDecision.Allow a && a.additionalContext() != null) {
                effectivePrompt = prompt + "\n\n[hook additionalContext]\n" + a.additionalContext();
            }
        }

        int priorCount = session.getMessages().size();
        Thread replThread = Thread.currentThread();
        AtomicBoolean turnActive = new AtomicBoolean(true);

        // While a turn runs the terminal is in cooked mode, so Ctrl+C raises
        // SIGINT — without this handler it kills the whole JVM instead of
        // cancelling the turn.
        sun.misc.SignalHandler previous = null;
        try {
            previous = sun.misc.Signal.handle(new sun.misc.Signal("INT"), sig -> {
                if (turnActive.get()) {
                    engine.requestAbort();
                    replThread.interrupt();
                }
            });
        } catch (Throwable ignored) {
            // Signal API unavailable on this JVM — Ctrl+C keeps default behavior
        }

        try {
            List<Message> updated = engine.run(session.getMessages(), effectivePrompt);
            session.addMessages(updated.subList(priorCount, updated.size()));
        } finally {
            turnActive.set(false);
            if (previous != null) {
                try {
                    sun.misc.Signal.handle(new sun.misc.Signal("INT"), previous);
                } catch (Throwable ignored) {
                }
            }
            // Clear a leftover interrupt flag so the next readLine isn't poisoned
            Thread.interrupted();
        }
        persistSession(screen);
        screen.println();
        return true;
    }

    /** Auto-save after every turn so a crash or kill never loses the conversation. */
    private void persistSession(TerminalScreen screen) {
        try {
            session.save(SessionManager.getSessionDirectory());
        } catch (IOException e) {
            if (!saveWarned) {
                saveWarned = true;
                screen.println(Ansi.DIM + "  (session auto-save failed: " + e.getMessage() + ")" + Ansi.RESET);
            }
        }
    }

    /**
     * Process a command result. Returns false if the REPL should exit its main loop
     * (EXIT and the Stop-hook path), letting the caller restore the terminal and
     * shut managers down.
     */
    private boolean handleCommandResult(CommandResult result, TerminalScreen screen,
                                        QueryEngine engine,
                                        Function<ToolRegistry, QueryEngine> engineFactory) {
        if (result.action() == CommandResult.Action.SUBMIT_PROMPT) {
            QueryEngine turnEngine = engine;
            if (result.allowedTools() != null && !result.allowedTools().isEmpty()) {
                Set<String> allowed = Set.copyOf(result.allowedTools());
                turnEngine = engineFactory.apply(
                        toolRegistry.filteredCopy(t -> allowed.contains(t.name())));
            }
            return runAgentTurn(result.output(), turnEngine, screen);
        }

        if (result.output() != null) {
            screen.println(result.output());
        }

        switch (result.action()) {
            case EXIT -> {
                screen.println();
                screen.println(Ansi.DIM + "  Goodbye!" + Ansi.RESET);
                screen.println();
                return false;
            }
            case CLEAR -> screen.print(Ansi.CLEAR_SCREEN + Ansi.HOME);
            case RESET -> screen.println();
            case CONTINUE -> screen.println();
            case SUBMIT_PROMPT -> { /* handled above */ }
        }
        return true;
    }

    /** Renders the model's plan and asks the user to approve leaving plan mode. */
    private boolean approvePlan(String plan, TerminalScreen screen) {
        screen.println();
        screen.println(Ansi.BOLD + Ansi.CYAN + "  ── Proposed plan ──" + Ansi.RESET);
        screen.println();
        screen.print(MarkdownRenderer.render(plan, screen.getWidth()));
        screen.println();
        screen.println("  " + Ansi.GREEN + "(y)" + Ansi.RESET + " approve and exit plan mode   "
                + Ansi.RED + "(n)" + Ansi.RESET + " reject, keep planning " + Ansi.DIM + "[default]" + Ansi.RESET);
        screen.print("  > ");
        screen.flush();

        int c = readSingleChar(screen);
        screen.println();
        boolean approved = c == 'y' || c == 'Y';
        screen.println(approved
                ? "  " + Ansi.GREEN + "(plan approved — plan mode off)" + Ansi.RESET
                : "  " + Ansi.YELLOW + "(plan rejected — still in plan mode)" + Ansi.RESET);
        return approved;
    }

    private int readSingleChar(TerminalScreen screen) {
        var previous = screen.enterRawMode();
        try {
            return screen.getTerminal().reader().read();
        } catch (IOException e) {
            return -1;
        } finally {
            screen.restoreAttributes(previous);
        }
    }

    private String promptString() {
        return Ansi.BOLD + Ansi.CYAN + "❯ " + Ansi.RESET;
    }
}
