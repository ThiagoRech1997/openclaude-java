package dev.openclaude.commands.impl;

import dev.openclaude.commands.*;
import dev.openclaude.core.model.ContentBlock;
import dev.openclaude.core.model.Message;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Exports the conversation to a markdown file.
 */
public class ExportCommand implements Command {
    @Override public String name() { return "export"; }
    @Override public String description() { return "Export conversation to a markdown file"; }

    @Override
    public CommandResult execute(String args, CommandContext ctx) {
        var messages = ctx.session().getMessages();
        if (messages.isEmpty()) {
            return CommandResult.text("  No messages to export.");
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String filename = args.isBlank()
                ? "conversation-" + timestamp + ".md"
                : args.trim();

        Path outputPath = ctx.workingDirectory().resolve(filename);

        StringBuilder md = new StringBuilder();
        md.append("# Conversation Export\n\n");
        md.append("- **Date**: ").append(LocalDateTime.now()).append('\n');
        md.append("- **Model**: ").append(ctx.config().model()).append('\n');
        md.append("- **Provider**: ").append(ctx.config().provider()).append('\n');
        md.append("- **Session**: ").append(ctx.session().getSessionId()).append('\n');
        md.append("- **Turns**: ").append(ctx.session().getTurnCount()).append('\n');
        md.append('\n');

        for (Message msg : messages) {
            String role = msg.role().value();
            md.append("## ").append(role.substring(0, 1).toUpperCase())
                    .append(role.substring(1)).append('\n').append('\n');

            for (ContentBlock block : msg.content()) {
                if (block instanceof ContentBlock.Text t) {
                    md.append(t.text()).append('\n');
                } else if (block instanceof ContentBlock.ToolUse tu) {
                    md.append("**Tool**: `").append(tu.name()).append("`\n\n");
                    md.append("```json\n").append(tu.input().toPrettyString()).append("\n```\n\n");
                } else if (block instanceof ContentBlock.ToolResult tr) {
                    md.append("**Result**");
                    if (tr.isError()) md.append(" (error)");
                    md.append(":\n\n```\n");
                    String content = tr.content();
                    if (content.length() > 2000) {
                        md.append(content, 0, 2000).append("\n... (truncated)");
                    } else {
                        md.append(content);
                    }
                    md.append("\n```\n\n");
                }
            }
            md.append("---\n\n");
        }

        try {
            Files.writeString(outputPath, md.toString());
            return CommandResult.text("  Exported " + messages.size() + " messages to " + outputPath);
        } catch (IOException e) {
            return CommandResult.text("  Failed to export: " + e.getMessage());
        }
    }
}
