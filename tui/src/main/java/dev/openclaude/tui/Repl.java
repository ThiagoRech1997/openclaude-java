package dev.openclaude.tui;

import dev.openclaude.commands.*;
import dev.openclaude.core.config.AppConfig;
import dev.openclaude.core.permissions.PermissionManager;
import dev.openclaude.core.session.SessionManager;
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

    public Repl(AppConfig config, LlmClient client, ToolRegistry toolRegistry,
                Path workingDirectory, String systemPrompt,
                CommandRegistry commandRegistry, PermissionManager permissions) {
        this.config = config;
        this.client = client;
        this.toolRegistry = toolRegistry;
        this.workingDirectory = workingDirectory;
        this.systemPrompt = systemPrompt;
        this.commandRegistry = commandRegistry;
        this.permissions = permissions;
        this.session = new SessionManager();
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
                    workingDirectory, screen.getWidth());

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
                        handleCommandResult(result, screen);
                        continue;
                    }
                }

                // Run the agent
                screen.println();
                session.incrementTurn();

                QueryEngine engine = new QueryEngine(
                        client, toolRegistry, config.model(), systemPrompt,
                        config.maxTokens(), workingDirectory, event -> {
                    display.handleEvent(event);
                    // Track usage
                    if (event instanceof dev.openclaude.engine.EngineEvent.Done done) {
                        session.addUsage(done.totalUsage());
                    }
                });

                engine.run(trimmed);
                screen.println();
            }
        } catch (IOException e) {
            System.err.println("Failed to initialize terminal: " + e.getMessage());
        }
    }

    private void handleCommandResult(CommandResult result, TerminalScreen screen) {
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
            case CLEAR -> {
                screen.print(Ansi.CLEAR_SCREEN + Ansi.HOME);
            }
            case RESET -> {
                // Session already cleared by the command
                screen.println();
            }
            case CONTINUE -> {
                screen.println();
            }
        }
    }

    private String promptString() {
        return Ansi.BOLD + Ansi.CYAN + "❯ " + Ansi.RESET;
    }
}
