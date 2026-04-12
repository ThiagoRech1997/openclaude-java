package dev.openclaude.commands.impl;

import dev.openclaude.commands.*;
import dev.openclaude.core.model.Usage;

public class CostCommand implements Command {
    @Override public String name() { return "cost"; }
    @Override public String description() { return "Show token usage and estimated cost"; }

    @Override
    public CommandResult execute(String args, CommandContext ctx) {
        Usage usage = ctx.session().getTotalUsage();
        double cost = ctx.session().estimateCostUsd();

        StringBuilder sb = new StringBuilder();
        sb.append("\n  Session cost:\n");
        sb.append("    Input tokens:  ").append(String.format("%,d", usage.inputTokens())).append('\n');
        sb.append("    Output tokens: ").append(String.format("%,d", usage.outputTokens())).append('\n');
        if (usage.cacheReadInputTokens() > 0) {
            sb.append("    Cache reads:   ").append(String.format("%,d", usage.cacheReadInputTokens())).append('\n');
        }
        sb.append("    Total tokens:  ").append(String.format("%,d", usage.totalTokens())).append('\n');
        sb.append("    Est. cost:     $").append(String.format("%.4f", cost)).append('\n');
        sb.append("    Turns:         ").append(ctx.session().getTurnCount()).append('\n');
        return CommandResult.text(sb.toString());
    }
}
