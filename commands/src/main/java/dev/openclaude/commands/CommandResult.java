package dev.openclaude.commands;

import java.util.List;

/**
 * Result from a command execution.
 *
 * <p>{@code allowedTools} only applies to {@link Action#SUBMIT_PROMPT}: when non-null
 * and non-empty, the submitted turn runs with the tool registry restricted to those
 * tool names (custom commands' {@code allowed-tools} frontmatter).
 */
public record CommandResult(
        String output,
        Action action,
        List<String> allowedTools
) {
    public CommandResult(String output, Action action) {
        this(output, action, null);
    }

    public enum Action {
        /** Just display output, continue REPL. */
        CONTINUE,
        /** Exit the REPL. */
        EXIT,
        /** Clear the screen. */
        CLEAR,
        /** Reset the conversation. */
        RESET,
        /** Feed `output` to the LLM as a user prompt (custom slash commands). */
        SUBMIT_PROMPT
    }

    public static CommandResult text(String output) {
        return new CommandResult(output, Action.CONTINUE);
    }

    public static CommandResult exit() {
        return new CommandResult(null, Action.EXIT);
    }

    public static CommandResult clear() {
        return new CommandResult(null, Action.CLEAR);
    }

    public static CommandResult reset(String output) {
        return new CommandResult(output, Action.RESET);
    }

    public static CommandResult submitPrompt(String prompt) {
        return new CommandResult(prompt, Action.SUBMIT_PROMPT);
    }

    public static CommandResult submitPrompt(String prompt, List<String> allowedTools) {
        return new CommandResult(prompt, Action.SUBMIT_PROMPT, allowedTools);
    }
}
