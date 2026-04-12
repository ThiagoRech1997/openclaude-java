package dev.openclaude.commands.impl;

import dev.openclaude.commands.*;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Diagnostic command — checks environment setup.
 */
public class DoctorCommand implements Command {
    @Override public String name() { return "doctor"; }
    @Override public String description() { return "Check environment and configuration"; }

    @Override
    public CommandResult execute(String args, CommandContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n  openclaude-java doctor\n");
        sb.append("  ").append("─".repeat(40)).append('\n');

        // Java version
        sb.append(check("Java version",
                System.getProperty("java.version"),
                true));

        // Provider
        sb.append(check("Provider",
                ctx.config().provider() + " / " + ctx.config().model(),
                true));

        // API key
        boolean hasKey = ctx.config().apiKey() != null && !ctx.config().apiKey().isBlank();
        sb.append(check("API key",
                hasKey ? "configured (****" + ctx.config().apiKey().substring(
                        Math.max(0, ctx.config().apiKey().length() - 4)) + ")" : "NOT SET",
                hasKey || "ollama".equals(ctx.config().provider())));

        // Git
        sb.append(check("git", commandAvailable("git", "--version"), true));

        // ripgrep
        boolean hasRg = commandAvailable("rg", "--version") != null;
        sb.append(check("ripgrep (rg)",
                hasRg ? "installed" : "not found (will use grep fallback)",
                hasRg));

        // Working directory
        sb.append(check("Working directory",
                ctx.workingDirectory().toString(),
                Files.isDirectory(ctx.workingDirectory())));

        // MCP config
        Path mcpConfig = ctx.workingDirectory().resolve(".mcp.json");
        sb.append(check(".mcp.json",
                Files.exists(mcpConfig) ? "found" : "not found",
                true)); // not required

        // Tools
        sb.append(check("Tools loaded",
                ctx.toolRegistry().size() + " tools",
                ctx.toolRegistry().size() > 0));

        // Settings
        Path settings = Path.of(System.getProperty("user.home"), ".claude", "settings.json");
        sb.append(check("~/.claude/settings.json",
                Files.exists(settings) ? "found" : "not found",
                true));

        // Plugins dir
        Path pluginsDir = Path.of(System.getProperty("user.home"), ".claude", "plugins");
        if (Files.isDirectory(pluginsDir)) {
            try {
                long count = Files.list(pluginsDir).filter(p -> p.toString().endsWith(".jar")).count();
                sb.append(check("Plugins", count + " JAR(s) in ~/.claude/plugins/", true));
            } catch (Exception e) {
                sb.append(check("Plugins", "error reading directory", false));
            }
        } else {
            sb.append(check("Plugins", "~/.claude/plugins/ not found", true));
        }

        sb.append('\n');
        return CommandResult.text(sb.toString());
    }

    private String check(String label, String value, boolean ok) {
        String icon = ok ? "✓" : "✗";
        String color = ok ? "\u001b[32m" : "\u001b[33m";
        return String.format("  %s%s\u001b[0m %-22s %s%n", color, icon, label, value != null ? value : "");
    }

    private String commandAvailable(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();
            byte[] output = p.getInputStream().readAllBytes();
            p.waitFor();
            if (p.exitValue() == 0) {
                String out = new String(output).trim();
                // Return first line
                int nl = out.indexOf('\n');
                return nl > 0 ? out.substring(0, nl).trim() : out;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
