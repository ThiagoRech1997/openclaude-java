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

        if (ctx.commandRegistry() != null) {
            for (Command cmd : ctx.commandRegistry().allCommands()) {
                String aliases = "";
                if (cmd.aliases().length > 0) {
                    aliases = " (" + String.join(", ", cmd.aliases()) + ")";
                }
                sb.append(String.format("    /%-13s%s  %s%n",
                        cmd.name(), aliases, cmd.description()));
            }
        } else {
            sb.append("    /help         Show this help\n");
            sb.append("    /clear        Clear the screen\n");
            sb.append("    /exit         Exit the REPL\n");
        }

        return CommandResult.text(sb.toString());
    }
}
