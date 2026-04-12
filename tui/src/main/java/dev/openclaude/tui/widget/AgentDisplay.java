package dev.openclaude.tui.widget;

import dev.openclaude.core.model.StreamEvent;
import dev.openclaude.engine.EngineEvent;
import dev.openclaude.tui.Ansi;
import dev.openclaude.tui.TerminalScreen;
import dev.openclaude.tui.render.MarkdownRenderer;

/**
 * Handles the display of agent responses, tool calls, and status.
 * Renders streaming text, tool execution progress, and final stats.
 */
public class AgentDisplay {

    private final TerminalScreen screen;
    private final Spinner spinner;
    private final StringBuilder currentResponse = new StringBuilder();
    private boolean isStreaming = false;

    public AgentDisplay(TerminalScreen screen) {
        this.screen = screen;
        this.spinner = new Spinner(screen.getTerminal().writer());
    }

    /**
     * Handle an engine event and render it to the terminal.
     */
    public void handleEvent(EngineEvent event) {
        if (event instanceof EngineEvent.Stream s) {
            handleStreamEvent(s.event());
        } else if (event instanceof EngineEvent.ToolExecutionStart tes) {
            handleToolStart(tes);
        } else if (event instanceof EngineEvent.ToolExecutionEnd tee) {
            handleToolEnd(tee);
        } else if (event instanceof EngineEvent.Done done) {
            handleDone(done);
        } else if (event instanceof EngineEvent.Error err) {
            handleError(err);
        }
    }

    private void handleStreamEvent(StreamEvent event) {
        if (event instanceof StreamEvent.TextDelta td) {
            if (!isStreaming) {
                isStreaming = true;
                spinner.stop();
            }
            screen.print(td.text());
            currentResponse.append(td.text());
        } else if (event instanceof StreamEvent.ThinkingDelta th) {
            if (!isStreaming) {
                isStreaming = true;
                spinner.stop();
            }
            screen.print(Ansi.DIM + th.thinking() + Ansi.RESET);
        } else if (event instanceof StreamEvent.MessageStart ms) {
            spinner.start("Thinking...");
        } else if (event instanceof StreamEvent.ToolUseStart tus) {
            spinner.stop();
            isStreaming = false;
        } else if (event instanceof StreamEvent.Error err) {
            spinner.stop();
            screen.println();
            screen.println(Ansi.RED + "✗ Stream error: " + err.message() + Ansi.RESET);
        }
    }

    private void handleToolStart(EngineEvent.ToolExecutionStart tes) {
        spinner.stop();
        if (isStreaming) {
            screen.println();
            isStreaming = false;
        }
        screen.println();

        String icon = getToolIcon(tes.toolName());
        screen.print(Ansi.CYAN + "  " + icon + " " + tes.toolName() + Ansi.RESET);
        spinner.start("Running " + tes.toolName() + "...");
    }

    private void handleToolEnd(EngineEvent.ToolExecutionEnd tee) {
        spinner.stop();
        if (tee.result().isError()) {
            screen.println(Ansi.RED + " ✗" + Ansi.RESET);
            // Show truncated error
            String error = tee.result().content();
            String[] lines = error.split("\n");
            int maxLines = Math.min(lines.length, 5);
            for (int i = 0; i < maxLines; i++) {
                screen.println(Ansi.DIM + "    " + truncate(lines[i], screen.getWidth() - 6) + Ansi.RESET);
            }
            if (lines.length > maxLines) {
                screen.println(Ansi.DIM + "    ... (" + (lines.length - maxLines) + " more lines)" + Ansi.RESET);
            }
        } else {
            screen.println(Ansi.GREEN + " ✓" + Ansi.RESET);
            // Show brief result preview
            String content = tee.result().content();
            if (content != null && !content.isBlank()) {
                String[] lines = content.split("\n");
                if (lines.length <= 3) {
                    for (String line : lines) {
                        screen.println(Ansi.DIM + "    " + truncate(line, screen.getWidth() - 6) + Ansi.RESET);
                    }
                } else {
                    screen.println(Ansi.DIM + "    " + truncate(lines[0], screen.getWidth() - 6) + Ansi.RESET);
                    screen.println(Ansi.DIM + "    ... (" + lines.length + " lines)" + Ansi.RESET);
                }
            }
        }
        screen.println();
        // Start spinner for next LLM call
        spinner.start("Thinking...");
    }

    private void handleDone(EngineEvent.Done done) {
        spinner.stop();
        if (isStreaming) {
            screen.println();
            isStreaming = false;
        }
        screen.println();

        String stats = String.format("tokens: %d in / %d out",
                done.totalUsage().inputTokens(),
                done.totalUsage().outputTokens());
        if (done.loopCount() > 1) {
            stats += " | " + done.loopCount() + " turns";
        }
        screen.println(Ansi.DIM + "  " + stats + Ansi.RESET);
        currentResponse.setLength(0);
    }

    private void handleError(EngineEvent.Error err) {
        spinner.stop();
        screen.println();
        screen.println(Ansi.RED + "  ✗ Error: " + err.message() + Ansi.RESET);
        currentResponse.setLength(0);
    }

    public void showWelcome(String provider, String model, int toolCount) {
        screen.println();
        screen.println(Ansi.BOLD + Ansi.CYAN + "  ╭─ openclaude-java" + Ansi.RESET + Ansi.DIM + " v0.1.0" + Ansi.RESET);
        screen.println(Ansi.CYAN + "  │" + Ansi.RESET + Ansi.DIM + "  " + provider + " / " + model + Ansi.RESET);
        screen.println(Ansi.CYAN + "  │" + Ansi.RESET + Ansi.DIM + "  " + toolCount + " tools available" + Ansi.RESET);
        screen.println(Ansi.CYAN + "  │" + Ansi.RESET + Ansi.DIM + "  cwd: " + System.getProperty("user.dir") + Ansi.RESET);
        screen.println(Ansi.CYAN + "  ╰─" + Ansi.RESET + Ansi.DIM + " Type /help for commands. Ctrl+D to exit." + Ansi.RESET);
        screen.println();
    }

    private String getToolIcon(String toolName) {
        return switch (toolName) {
            case "Bash" -> "⚡";
            case "Read" -> "📖";
            case "Write" -> "✏️";
            case "Edit" -> "📝";
            case "Glob" -> "🔍";
            case "Grep" -> "🔎";
            default -> "🔧";
        };
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (maxLen <= 0) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "…";
    }
}
