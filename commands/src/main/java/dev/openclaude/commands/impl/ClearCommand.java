package dev.openclaude.commands.impl;

import dev.openclaude.commands.*;

public class ClearCommand implements Command {
    @Override public String name() { return "clear"; }
    @Override public String description() { return "Clear the screen"; }
    @Override public String[] aliases() { return new String[]{"cls"}; }

    @Override
    public CommandResult execute(String args, CommandContext ctx) {
        return CommandResult.clear();
    }
}
