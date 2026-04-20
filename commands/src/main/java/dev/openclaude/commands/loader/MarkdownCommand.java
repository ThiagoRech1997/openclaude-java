package dev.openclaude.commands.loader;

import dev.openclaude.commands.Command;
import dev.openclaude.commands.CommandContext;
import dev.openclaude.commands.CommandResult;

import java.util.List;

/**
 * A slash command defined by a markdown file in .claude/commands/.
 *
 * <p>Invoking {@code /<name> args} substitutes {@code $ARGUMENTS} in {@link #body} with the
 * raw args string and submits the result as a user prompt to the LLM.
 */
public record MarkdownCommand(
        String name,
        String descriptionText,
        String argumentHint,
        List<String> allowedTools,
        String body,
        String source
) implements Command {

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        StringBuilder sb = new StringBuilder(descriptionText == null ? "" : descriptionText);
        if (argumentHint != null && !argumentHint.isBlank()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append('[').append(argumentHint).append(']');
        }
        if (source != null && !source.isBlank()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append('(').append(source).append(')');
        }
        return sb.toString();
    }

    @Override
    public CommandResult execute(String args, CommandContext context) {
        String safeArgs = args == null ? "" : args;
        String expanded = body.replace("$ARGUMENTS", safeArgs);
        return CommandResult.submitPrompt(expanded);
    }
}
