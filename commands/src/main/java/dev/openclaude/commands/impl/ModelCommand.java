package dev.openclaude.commands.impl;

import dev.openclaude.commands.*;

public class ModelCommand implements Command {
    @Override public String name() { return "model"; }
    @Override public String description() { return "Show current model and provider"; }

    @Override
    public CommandResult execute(String args, CommandContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n  Provider: ").append(ctx.config().provider()).append('\n');
        sb.append("  Model:    ").append(ctx.config().model()).append('\n');
        sb.append("  Base URL: ").append(ctx.config().baseUrl()).append('\n');
        return CommandResult.text(sb.toString());
    }
}
