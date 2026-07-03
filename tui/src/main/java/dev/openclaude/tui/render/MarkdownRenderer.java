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

        Stream stream = new Stream(terminalWidth);
        StringBuilder result = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            result.append(stream.renderLine(line));
        }
        return result.toString();
    }

    /**
     * Stateful line-at-a-time renderer for streaming output: keeps the code-fence
     * state across calls so ``` blocks render correctly while text arrives.
     */
    public static final class Stream {

        private final int terminalWidth;
        private boolean inCodeBlock = false;

        public Stream(int terminalWidth) {
            this.terminalWidth = terminalWidth;
        }

        /** Forget fence state — call between messages. */
        public void reset() {
            inCodeBlock = false;
        }

        /**
         * Render one line of markdown (no trailing newline in the input);
         * the returned string ends with a newline.
         */
        public String renderLine(String line) {
            StringBuilder result = new StringBuilder();

            if (line.startsWith("```")) {
                inCodeBlock = !inCodeBlock;
                if (inCodeBlock) {
                    String codeLang = line.substring(3).trim();
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
                return result.toString();
            }

            if (inCodeBlock) {
                result.append(Ansi.DIM).append("│ ").append(Ansi.RESET);
                result.append(line);
                result.append('\n');
                return result.toString();
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
            return result.toString();
        }
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
