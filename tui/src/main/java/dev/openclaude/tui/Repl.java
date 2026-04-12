package dev.openclaude.tui;

import dev.openclaude.core.config.AppConfig;
import dev.openclaude.engine.QueryEngine;
import dev.openclaude.llm.LlmClient;
import dev.openclaude.tools.ToolRegistry;
import dev.openclaude.tui.widget.AgentDisplay;
import dev.openclaude.tui.widget.TextInput;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Interactive REPL (Read-Eval-Print Loop) for the coding agent.
 * Handles user input, sends to QueryEngine, and displays results.
 */
public class Repl {

    private final AppConfig config;
    private final LlmClient client;
    private final ToolRegistry toolRegistry;
    private final Path workingDirectory;
    private final String systemPrompt;

    public Repl(AppConfig config, LlmClient client, ToolRegistry toolRegistry,
                Path workingDirectory, String systemPrompt) {
        this.config = config;
        this.client = client;
        this.toolRegistry = toolRegistry;
        this.workingDirectory = workingDirectory;
        this.systemPrompt = systemPrompt;
    }

    /**
     * Start the interactive REPL loop.
     */
    public void start() {
        try (TerminalScreen screen = new TerminalScreen()) {
            AgentDisplay display = new AgentDisplay(screen);
            TextInput input = new TextInput(screen.getTerminal(), promptString());

            display.showWelcome(config.provider(), config.model(), toolRegistry.size());

            while (true) {
                String userInput = input.readLineSafe();

                if (userInput == null) {
                    // Ctrl+D or Ctrl+C
                    screen.println();
                    screen.println(Ansi.DIM + "  Goodbye!" + Ansi.RESET);
                    screen.println();
                    break;
                }

                String trimmed = userInput.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }

                // Handle built-in commands
                if (trimmed.startsWith("/")) {
                    if (handleCommand(trimmed, screen)) {
                        continue;
                    }
                }

                // Show user prompt echoed
                screen.println();

                // Run the agent
                QueryEngine engine = new QueryEngine(
                        client, toolRegistry, config.model(), systemPrompt,
                        config.maxTokens(), workingDirectory, display::handleEvent
                );

                engine.run(trimmed);

                screen.println();
            }
        } catch (IOException e) {
            System.err.println("Failed to initialize terminal: " + e.getMessage());
        }
    }

    /**
     * Handle slash commands. Returns true if the command was handled.
     */
    private boolean handleCommand(String command, TerminalScreen screen) {
        return switch (command.toLowerCase()) {
            case "/exit", "/quit", "/q" -> {
                screen.println();
                screen.println(Ansi.DIM + "  Goodbye!" + Ansi.RESET);
                screen.println();
                System.exit(0);
                yield true;
            }
            case "/help", "/h" -> {
                screen.println();
                screen.println(Ansi.BOLD + "  Available commands:" + Ansi.RESET);
                screen.println(Ansi.CYAN + "    /help" + Ansi.RESET + "     Show this help");
                screen.println(Ansi.CYAN + "    /clear" + Ansi.RESET + "    Clear the screen");
                screen.println(Ansi.CYAN + "    /model" + Ansi.RESET + "    Show current model");
                screen.println(Ansi.CYAN + "    /tools" + Ansi.RESET + "    List available tools");
                screen.println(Ansi.CYAN + "    /exit" + Ansi.RESET + "     Exit the REPL");
                screen.println();
                yield true;
            }
            case "/clear", "/cls" -> {
                screen.print(Ansi.CLEAR_SCREEN + Ansi.HOME);
                yield true;
            }
            case "/model" -> {
                screen.println();
                screen.println(Ansi.DIM + "  Provider: " + Ansi.RESET + config.provider());
                screen.println(Ansi.DIM + "  Model: " + Ansi.RESET + config.model());
                screen.println();
                yield true;
            }
            case "/tools" -> {
                screen.println();
                screen.println(Ansi.BOLD + "  Available tools (" + toolRegistry.size() + "):" + Ansi.RESET);
                for (var tool : toolRegistry.allTools()) {
                    String ro = tool.isReadOnly() ? Ansi.DIM + " (read-only)" + Ansi.RESET : "";
                    screen.println(Ansi.CYAN + "    " + tool.name() + Ansi.RESET + " — " + tool.description() + ro);
                }
                screen.println();
                yield true;
            }
            default -> {
                screen.println(Ansi.YELLOW + "  Unknown command: " + command + Ansi.RESET);
                screen.println(Ansi.DIM + "  Type /help for available commands." + Ansi.RESET);
                yield true;
            }
        };
    }

    private String promptString() {
        return Ansi.BOLD + Ansi.CYAN + "❯ " + Ansi.RESET;
    }
}
