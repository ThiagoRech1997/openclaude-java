package dev.openclaude.commands;

/**
 * Result from a command execution.
 */
public record CommandResult(
        String output,
        Action action
) {
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
}
