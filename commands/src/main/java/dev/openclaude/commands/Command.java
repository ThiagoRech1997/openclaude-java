package dev.openclaude.commands;

/**
 * Interface for slash commands (e.g., /help, /clear, /model).
 */
public interface Command {

    /** The command name without the slash (e.g., "help", "clear"). */
    String name();

    /** Short description shown in /help. */
    String description();

    /** Optional aliases (e.g., "q" for "exit"). */
    default String[] aliases() {
        return new String[0];
    }

    /**
     * Execute the command.
     *
     * @param args the raw argument string after the command name
     * @param context execution context
     * @return result to display, or null for no output
     */
    CommandResult execute(String args, CommandContext context);
}
