package dev.openclaude.tui.widget;

import dev.openclaude.tui.Ansi;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;

/**
 * Interactive text input widget using JLine's LineReader.
 * Supports line editing, history, and Ctrl+C/Ctrl+D handling.
 */
public class TextInput {

    private final LineReader reader;
    private final String prompt;

    public TextInput(Terminal terminal, String prompt) {
        this.prompt = prompt;
        this.reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                .build();
    }

    /**
     * Read a line of input from the user.
     *
     * @return the input string, or null if the user pressed Ctrl+D (EOF)
     * @throws UserInterruptException if the user pressed Ctrl+C
     */
    public String readLine() {
        try {
            return reader.readLine(prompt);
        } catch (EndOfFileException e) {
            return null; // Ctrl+D
        }
    }

    /**
     * Read input, returning null on both Ctrl+C and Ctrl+D.
     */
    public String readLineSafe() {
        try {
            return reader.readLine(prompt);
        } catch (EndOfFileException | UserInterruptException e) {
            return null;
        }
    }
}
