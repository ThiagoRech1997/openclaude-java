package dev.openclaude.commands.impl;

import dev.openclaude.commands.*;

public class ExitCommand implements Command {
    @Override public String name() { return "exit"; }
    @Override public String description() { return "Exit the REPL"; }
    @Override public String[] aliases() { return new String[]{"quit", "q"}; }

    @Override
    public CommandResult execute(String args, CommandContext ctx) {
        return CommandResult.exit();
    }
}
