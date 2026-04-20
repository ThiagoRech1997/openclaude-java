package dev.openclaude.commands;

import dev.openclaude.commands.impl.*;
import dev.openclaude.commands.loader.CustomCommandLoader;
import dev.openclaude.commands.loader.MarkdownCommand;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Creates a CommandRegistry with all built-in commands registered.
 */
public final class CommandRegistryFactory {

    public CommandRegistry create() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(new HelpCommand());
        registry.register(new ClearCommand());
        registry.register(new ExitCommand());
        registry.register(new ModelCommand());
        registry.register(new ToolsCommand());
        registry.register(new CostCommand());
        registry.register(new PermissionsCommand());
        registry.register(new ResetCommand());
        registry.register(new StatusCommand());
        registry.register(new CompactCommand());
        registry.register(new DiffCommand());
        registry.register(new ExportCommand());
        registry.register(new DoctorCommand());
        registry.register(new DocsCommand());
        registry.register(new MemoryCommand());
        return registry;
    }

    /**
     * Build a registry with built-ins plus custom markdown commands discovered in
     * {@code <cwd>/.claude/commands/} and {@code ~/.claude/commands/}.
     *
     * <p>Custom commands never overwrite built-ins (collisions are skipped with a stderr warning).
     */
    public CommandRegistry create(Path cwd) {
        CommandRegistry registry = create();

        // Snapshot built-in names before loading customs. The collision check must only reject
        // built-ins — otherwise a project-local .md that shadows a user-global one would be
        // treated as a collision and skipped (losing the intended project > user precedence).
        Set<String> builtins = new HashSet<>();
        for (Command c : registry.allCommands()) {
            builtins.add(c.name().toLowerCase());
        }

        for (MarkdownCommand cmd : new CustomCommandLoader().load(cwd)) {
            if (builtins.contains(cmd.name().toLowerCase())) {
                System.err.println("[custom-commands] skipping /" + cmd.name()
                        + " — name collides with a built-in");
                continue;
            }
            registry.register(cmd);
        }
        return registry;
    }
}
