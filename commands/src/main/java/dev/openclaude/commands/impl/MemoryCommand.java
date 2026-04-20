package dev.openclaude.commands.impl;

import dev.openclaude.commands.*;
import dev.openclaude.core.config.ClaudeMdLoader;

public class MemoryCommand implements Command {
    @Override public String name() { return "memory"; }
    @Override public String description() { return "Show loaded CLAUDE.md files"; }
    @Override public String[] aliases() { return new String[] { "claude-md" }; }

    @Override
    public CommandResult execute(String args, CommandContext ctx) {
        var result = ClaudeMdLoader.load(ctx.workingDirectory());
        StringBuilder sb = new StringBuilder();
        sb.append('\n');
        if (result.isEmpty()) {
            sb.append("  No CLAUDE.md files loaded.\n");
            sb.append("  Searched:\n");
            sb.append("    ~/.claude/CLAUDE.md\n");
            sb.append("    ").append(ctx.workingDirectory()).append("/.claude/CLAUDE.md\n");
            sb.append("    ").append(ctx.workingDirectory()).append("/CLAUDE.md\n");
            return CommandResult.text(sb.toString());
        }

        sb.append("  Loaded CLAUDE.md sources (").append(result.sources().size()).append("):\n\n");
        for (var src : result.sources()) {
            int lines = (int) src.content().lines().count();
            sb.append("  • ").append(src.path())
              .append(" (").append(src.label()).append(", ")
              .append(lines).append(" line").append(lines == 1 ? "" : "s").append(")\n");
        }
        sb.append('\n');
        for (var src : result.sources()) {
            sb.append("  ─── ").append(src.path()).append(" ───\n");
            src.content().lines().forEach(line -> sb.append("  ").append(line).append('\n'));
            sb.append('\n');
        }
        return CommandResult.text(sb.toString());
    }
}
