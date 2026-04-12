package dev.openclaude.commands;

import dev.openclaude.commands.impl.*;

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
        return registry;
    }
}
