package dev.openclaude.tui.render;

import dev.openclaude.tui.Ansi;

/**
 * Simple inline markdown renderer for terminal output.
 * Handles bold, italic, code, and headers with ANSI colors.
 */
public final class MarkdownRenderer {

    private MarkdownRenderer() {}

    /**
     * Render markdown text with ANSI formatting.
     * Handles: headers (#), bold (**), italic (*), inline code (`), code blocks (```).
     */
    public static String render(String text, int terminalWidth) {
        if (text == null || text.isEmpty()) return "";

        StringBuilder result = new StringBuilder();
        String[] lines = text.split("\n", -1);
        boolean inCodeBlock = false;
        String codeLang = "";

        for (String line : lines) {
            if (line.startsWith("```")) {
                inCodeBlock = !inCodeBlock;
                if (inCodeBlock) {
                    codeLang = line.substring(3).trim();
                    result.append(Ansi.DIM).append("┌─");
                    if (!codeLang.isEmpty()) {
                        result.append(" ").append(codeLang).append(" ");
                    }
                    int remaining = terminalWidth - 4 - codeLang.length();
                    result.append("─".repeat(Math.max(0, remaining)));
                    result.append(Ansi.RESET).append('\n');
                } else {
                    result.append(Ansi.DIM).append("└");
                    result.append("─".repeat(Math.max(0, terminalWidth - 2)));
                    result.append(Ansi.RESET).append('\n');
                }
                continue;
            }

            if (inCodeBlock) {
                result.append(Ansi.DIM).append("│ ").append(Ansi.RESET);
                result.append(line);
                result.append('\n');
                continue;
            }

            // Headers
            if (line.startsWith("### ")) {
                result.append(Ansi.BOLD).append(Ansi.YELLOW).append(line.substring(4)).append(Ansi.RESET).append('\n');
            } else if (line.startsWith("## ")) {
                result.append(Ansi.BOLD).append(Ansi.CYAN).append(line.substring(3)).append(Ansi.RESET).append('\n');
            } else if (line.startsWith("# ")) {
                result.append(Ansi.BOLD).append(Ansi.BRIGHT_MAGENTA).append(line.substring(2)).append(Ansi.RESET).append('\n');
            } else if (line.startsWith("- ") || line.startsWith("* ")) {
                // Bullet points
                result.append(Ansi.CYAN).append("  • ").append(Ansi.RESET);
                result.append(renderInline(line.substring(2)));
                result.append('\n');
            } else if (line.matches("^\\d+\\.\\s.*")) {
                // Numbered list
                result.append(Ansi.CYAN).append("  ").append(Ansi.RESET);
                result.append(renderInline(line));
                result.append('\n');
            } else if (line.startsWith("> ")) {
                // Blockquote
                result.append(Ansi.DIM).append("  │ ").append(Ansi.ITALIC).append(line.substring(2)).append(Ansi.RESET).append('\n');
            } else if (line.startsWith("---") || line.startsWith("***")) {
                // Horizontal rule
                result.append(Ansi.DIM).append("─".repeat(Math.min(terminalWidth - 2, 60))).append(Ansi.RESET).append('\n');
            } else {
                result.append(renderInline(line)).append('\n');
            }
        }

        return result.toString();
    }

    /**
     * Render inline markdown: bold, italic, code.
     */
    static String renderInline(String text) {
        // Inline code `...`
        text = text.replaceAll("`([^`]+)`", Ansi.BG_GRAY + Ansi.WHITE + " $1 " + Ansi.RESET);
        // Bold **...**
        text = text.replaceAll("\\*\\*([^*]+)\\*\\*", Ansi.BOLD + "$1" + Ansi.RESET);
        // Italic *...*
        text = text.replaceAll("(?<!\\*)\\*([^*]+)\\*(?!\\*)", Ansi.ITALIC + "$1" + Ansi.RESET);
        return text;
    }
}
