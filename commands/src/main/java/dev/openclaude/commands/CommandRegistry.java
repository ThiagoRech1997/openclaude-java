package dev.openclaude.commands;

import java.util.*;

/**
 * Registry of all available slash commands.
 */
public final class CommandRegistry {

    private final Map<String, Command> commands = new LinkedHashMap<>();
    private final Map<String, String> aliasMap = new HashMap<>();

    public void register(Command command) {
        commands.put(command.name().toLowerCase(), command);
        for (String alias : command.aliases()) {
            aliasMap.put(alias.toLowerCase(), command.name().toLowerCase());
        }
    }

    /**
     * Find a command by name or alias.
     */
    public Optional<Command> find(String name) {
        String lower = name.toLowerCase();
        Command cmd = commands.get(lower);
        if (cmd != null) return Optional.of(cmd);

        String resolved = aliasMap.get(lower);
        if (resolved != null) return Optional.of(commands.get(resolved));

        return Optional.empty();
    }

    /**
     * Parse and execute a slash command string (e.g., "/help" or "/model gpt-4o").
     *
     * @return the result, or null if not a recognized command
     */
    public CommandResult dispatch(String input, CommandContext context) {
        if (!input.startsWith("/")) return null;

        String[] parts = input.substring(1).split("\\s+", 2);
        String cmdName = parts[0];
        String args = parts.length > 1 ? parts[1] : "";

        Optional<Command> cmd = find(cmdName);
        if (cmd.isEmpty()) {
            return CommandResult.text("Unknown command: /" + cmdName + ". Type /help for available commands.");
        }

        return cmd.get().execute(args, context);
    }

    public Collection<Command> allCommands() {
        return Collections.unmodifiableCollection(commands.values());
    }
}
