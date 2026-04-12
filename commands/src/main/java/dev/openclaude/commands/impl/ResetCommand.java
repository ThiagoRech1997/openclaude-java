package dev.openclaude.commands.impl;

import dev.openclaude.commands.*;

public class ResetCommand implements Command {
    @Override public String name() { return "reset"; }
    @Override public String description() { return "Reset the conversation"; }
    @Override public String[] aliases() { return new String[]{"new"}; }

    @Override
    public CommandResult execute(String args, CommandContext ctx) {
        ctx.session().clearMessages();
        return CommandResult.reset("  Conversation reset. Starting fresh.");
    }
}
