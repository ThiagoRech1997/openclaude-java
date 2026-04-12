package dev.openclaude.commands.impl;

import dev.openclaude.commands.*;
import dev.openclaude.core.model.Usage;

import java.time.Duration;
import java.time.Instant;

public class StatusCommand implements Command {
    @Override public String name() { return "status"; }
    @Override public String description() { return "Show session status"; }

    @Override
    public CommandResult execute(String args, CommandContext ctx) {
        var session = ctx.session();
        Usage usage = session.getTotalUsage();
        Duration elapsed = Duration.between(session.getStartTime(), Instant.now());

        StringBuilder sb = new StringBuilder();
        sb.append("\n  Session: ").append(session.getSessionId()).append('\n');
        sb.append("  Duration: ").append(formatDuration(elapsed)).append('\n');
        sb.append("  Turns: ").append(session.getTurnCount()).append('\n');
        sb.append("  Messages: ").append(session.getMessages().size()).append('\n');
        sb.append("  Tokens: ").append(String.format("%,d", usage.totalTokens())).append('\n');
        sb.append("  Provider: ").append(ctx.config().provider()).append('\n');
        sb.append("  Model: ").append(ctx.config().model()).append('\n');
        sb.append("  Permission mode: ").append(ctx.permissions().getMode()).append('\n');
        sb.append("  Working dir: ").append(ctx.workingDirectory()).append('\n');
        return CommandResult.text(sb.toString());
    }

    private String formatDuration(Duration d) {
        long hours = d.toHours();
        long minutes = d.toMinutesPart();
        long seconds = d.toSecondsPart();
        if (hours > 0) return String.format("%dh %dm %ds", hours, minutes, seconds);
        if (minutes > 0) return String.format("%dm %ds", minutes, seconds);
        return String.format("%ds", seconds);
    }
}
