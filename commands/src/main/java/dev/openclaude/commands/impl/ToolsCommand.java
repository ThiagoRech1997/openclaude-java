package dev.openclaude.commands.impl;

import dev.openclaude.commands.*;
import dev.openclaude.tools.Tool;

public class ToolsCommand implements Command {
    @Override public String name() { return "tools"; }
    @Override public String description() { return "List available tools"; }

    @Override
    public CommandResult execute(String args, CommandContext ctx) {
        var tools = ctx.toolRegistry().allTools();
        StringBuilder sb = new StringBuilder();
        sb.append("\n  Available tools (").append(tools.size()).append("):\n");
        for (Tool tool : tools) {
            String ro = tool.isReadOnly() ? " (read-only)" : "";
            String prefix = tool.name().startsWith("mcp__") ? " [MCP]" : "";
            sb.append("    ").append(tool.name()).append(prefix).append(ro)
              .append(" — ").append(tool.description()).append('\n');
        }
        return CommandResult.text(sb.toString());
    }
}
