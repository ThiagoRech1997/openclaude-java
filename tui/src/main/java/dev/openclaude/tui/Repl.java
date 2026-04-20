package dev.openclaude.tui;

import dev.openclaude.commands.*;
import dev.openclaude.core.config.AppConfig;
import dev.openclaude.core.hooks.HookDecision;
import dev.openclaude.core.hooks.HookExecutor;
import dev.openclaude.core.permissions.PermissionManager;
import dev.openclaude.core.session.SessionManager;
import dev.openclaude.engine.BackgroundAgentManager;
import dev.openclaude.engine.QueryEngine;
import dev.openclaude.llm.LlmClient;
import dev.openclaude.tools.ToolRegistry;
import dev.openclaude.tui.widget.AgentDisplay;
import dev.openclaude.tui.widget.TextInput;

import java.io.IOException;
import java.nio.file.Path;

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
        this.config = config;
        this.client = client;
        this.toolRegistry = toolRegistry;
        this.workingDirectory = workingDirectory;
        this.systemPrompt = systemPrompt;
        this.commandRegistry = commandRegistry;
        this.permissions = permissions;
        this.session = new SessionManager();
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

            CommandContext cmdCtx = new CommandContext(
                    config, toolRegistry, permissions, session,
                    workingDirectory, screen.getWidth(), commandRegistry);

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

                // Handle slash commands
                if (trimmed.startsWith("/")) {
                    CommandResult result = commandRegistry.dispatch(trimmed, cmdCtx);
                    if (result != null) {
                        if (!handleCommandResult(result, screen, display)) break;
                        continue;
                    }
                }

                if (!runAgentTurn(trimmed, display, screen)) break;
            }
        } catch (IOException e) {
            System.err.println("Failed to initialize terminal: " + e.getMessage());
        }
    }

    /**
     * Run one agent turn on {@code prompt}. Returns false if the hook pipeline asked the REPL to stop.
     */
    private boolean runAgentTurn(String prompt, AgentDisplay display,
                                 TerminalScreen screen) {
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

        QueryEngine engine = new QueryEngine(
                client, toolRegistry, config.model(), systemPrompt,
                config.maxTokens(), workingDirectory, event -> {
            display.handleEvent(event);
            if (event instanceof dev.openclaude.engine.EngineEvent.Done done) {
                session.addUsage(done.totalUsage());
            }
        }, backgroundManager, hooks);

        engine.run(effectivePrompt);
        screen.println();
        return true;
    }

    /**
     * Process a command result. Returns false if the REPL should exit its main loop
     * (only the Stop-hook path does so today — EXIT still calls System.exit).
     */
    private boolean handleCommandResult(CommandResult result, TerminalScreen screen,
                                        AgentDisplay display) {
        if (result.action() == CommandResult.Action.SUBMIT_PROMPT) {
            return runAgentTurn(result.output(), display, screen);
        }

        if (result.output() != null) {
            screen.println(result.output());
        }

        switch (result.action()) {
            case EXIT -> {
                screen.println();
                screen.println(Ansi.DIM + "  Goodbye!" + Ansi.RESET);
                screen.println();
                System.exit(0);
            }
            case CLEAR -> screen.print(Ansi.CLEAR_SCREEN + Ansi.HOME);
            case RESET -> screen.println();
            case CONTINUE -> screen.println();
            case SUBMIT_PROMPT -> { /* handled above */ }
        }
        return true;
    }

    private String promptString() {
        return Ansi.BOLD + Ansi.CYAN + "❯ " + Ansi.RESET;
    }
}
