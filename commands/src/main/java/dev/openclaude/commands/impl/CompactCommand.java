package dev.openclaude.commands.impl;

import dev.openclaude.commands.*;

public class CompactCommand implements Command {
    @Override public String name() { return "compact"; }
    @Override public String description() { return "Summarize conversation to save context window"; }

    @Override
    public CommandResult execute(String args, CommandContext ctx) {
        int msgCount = ctx.session().getMessages().size();
        if (msgCount <= 2) {
            return CommandResult.text("  Conversation is already small (" + msgCount + " messages). Nothing to compact.");
        }

        // For now, just clear old messages and keep the summary idea
        // A full implementation would send messages to the LLM for summarization
        return CommandResult.text("  Compaction not yet implemented. "
                + "Current conversation has " + msgCount + " messages.\n"
                + "  Use /reset to start fresh.");
    }
}
