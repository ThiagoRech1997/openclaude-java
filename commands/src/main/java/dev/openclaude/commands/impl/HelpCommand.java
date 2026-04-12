package dev.openclaude.commands.impl;

import dev.openclaude.commands.*;

public class HelpCommand implements Command {
    @Override public String name() { return "help"; }
    @Override public String description() { return "Show available commands"; }
    @Override public String[] aliases() { return new String[]{"h", "?"}; }

    @Override
    public CommandResult execute(String args, CommandContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n  Available commands:\n");
        sb.append("    /help         Show this help\n");
        sb.append("    /clear        Clear the screen\n");
        sb.append("    /model        Show current model and provider\n");
        sb.append("    /tools        List available tools\n");
        sb.append("    /cost         Show token usage and estimated cost\n");
        sb.append("    /permissions  Show/change permission mode\n");
        sb.append("    /status       Show session status\n");
        sb.append("    /compact      Summarize conversation to save context\n");
        sb.append("    /reset        Reset the conversation\n");
        sb.append("    /exit         Exit the REPL\n");
        return CommandResult.text(sb.toString());
    }
}
