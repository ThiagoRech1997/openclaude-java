package dev.openclaude.commands.impl;

import dev.openclaude.commands.*;
import dev.openclaude.core.model.Usage;
import dev.openclaude.core.session.CostTracker;

public class CostCommand implements Command {
    @Override public String name() { return "cost"; }
    @Override public String description() { return "Show token usage and estimated cost"; }

    @Override
    public CommandResult execute(String args, CommandContext ctx) {
        Usage usage = ctx.session().getTotalUsage();
        String model = ctx.config().model();

        StringBuilder sb = new StringBuilder();
        sb.append("\n  Session cost breakdown (").append(model).append("):\n");
        sb.append(CostTracker.formatBreakdown(usage, model)).append('\n');
        sb.append(String.format("  Turns: %d%n", ctx.session().getTurnCount()));
        return CommandResult.text(sb.toString());
    }
}
