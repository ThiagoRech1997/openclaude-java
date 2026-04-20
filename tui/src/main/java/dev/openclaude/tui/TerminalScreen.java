package dev.openclaude.tui;

import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.io.PrintWriter;

/**
 * Abstraction over the terminal, providing raw mode control and output.
 * Wraps JLine's Terminal for cross-platform terminal access.
 */
public class TerminalScreen implements AutoCloseable {

    private final Terminal terminal;
    private final PrintWriter writer;
    private final int defaultWidth;
    private final int defaultHeight;

    public TerminalScreen() throws IOException {
        this.terminal = TerminalBuilder.builder()
                .system(true)
                .jansi(true)
                .build();
        this.writer = terminal.writer();
        this.defaultWidth = 120;
        this.defaultHeight = 40;
    }

    public Terminal getTerminal() {
        return terminal;
    }

    public int getWidth() {
        int w = terminal.getWidth();
        return w > 0 ? w : defaultWidth;
    }

    public int getHeight() {
        int h = terminal.getHeight();
        return h > 0 ? h : defaultHeight;
    }

    public void print(String text) {
        writer.print(text);
        writer.flush();
    }

    public void println(String text) {
        writer.println(text);
        writer.flush();
    }

    public void println() {
        writer.println();
        writer.flush();
    }

    public void hideCursor() {
        print(Ansi.HIDE_CURSOR);
    }

    public void showCursor() {
        print(Ansi.SHOW_CURSOR);
    }

    public void clearLine() {
        print("\r" + Ansi.CLEAR_LINE);
    }

    public void flush() {
        writer.flush();
    }

    /**
     * Enter raw mode for character-by-character input. Returns the previous
     * attributes so the caller can restore them via {@link #restoreAttributes}.
     */
    public Attributes enterRawMode() {
        return terminal.enterRawMode();
    }

    public void restoreAttributes(Attributes attributes) {
        if (attributes != null) {
            terminal.setAttributes(attributes);
        }
    }

    @Override
    public void close() {
        showCursor();
        try {
            terminal.close();
        } catch (IOException e) {
            // ignore
        }
    }
}
