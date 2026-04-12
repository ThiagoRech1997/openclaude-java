package dev.openclaude.commands.impl;

import dev.openclaude.commands.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Shows git diff of uncommitted changes in the working directory.
 */
public class DiffCommand implements Command {
    @Override public String name() { return "diff"; }
    @Override public String description() { return "Show git diff of uncommitted changes"; }

    @Override
    public CommandResult execute(String args, CommandContext ctx) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "diff", "--stat");
            pb.directory(ctx.workingDirectory().toFile());
            pb.redirectErrorStream(true);

            Process proc = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append("  ").append(line).append('\n');
                }
            }
            proc.waitFor();

            if (output.length() == 0) {
                return CommandResult.text("\n  No uncommitted changes.\n");
            }

            return CommandResult.text("\n  Git diff (--stat):\n" + output);

        } catch (Exception e) {
            return CommandResult.text("  Failed to run git diff: " + e.getMessage());
        }
    }
}
