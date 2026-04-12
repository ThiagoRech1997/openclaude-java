package dev.openclaude.commands.impl;

import dev.openclaude.commands.*;
import dev.openclaude.core.permissions.PermissionManager;
import dev.openclaude.core.permissions.PermissionMode;

public class PermissionsCommand implements Command {
    @Override public String name() { return "permissions"; }
    @Override public String description() { return "Show and manage permission settings"; }
    @Override public String[] aliases() { return new String[]{"perms"}; }

    @Override
    public CommandResult execute(String args, CommandContext ctx) {
        PermissionManager pm = ctx.permissions();
        String trimmed = args.trim();

        // Sub-commands: /permissions auto, /permissions default, /permissions plan
        if (!trimmed.isEmpty()) {
            return switch (trimmed.toLowerCase()) {
                case "auto" -> {
                    pm.setMode(PermissionMode.AUTO_APPROVE);
                    yield CommandResult.text("  Permission mode set to: AUTO_APPROVE");
                }
                case "default" -> {
                    pm.setMode(PermissionMode.DEFAULT);
                    yield CommandResult.text("  Permission mode set to: DEFAULT");
                }
                case "plan" -> {
                    pm.setMode(PermissionMode.PLAN);
                    yield CommandResult.text("  Permission mode set to: PLAN (read-only)");
                }
                case "deny" -> {
                    pm.setMode(PermissionMode.AUTO_DENY);
                    yield CommandResult.text("  Permission mode set to: AUTO_DENY");
                }
                default -> CommandResult.text("  Usage: /permissions [auto|default|plan|deny]");
            };
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n  Permission mode: ").append(pm.getMode()).append('\n');

        if (!pm.getAllowedTools().isEmpty()) {
            sb.append("  Always allow: ").append(String.join(", ", pm.getAllowedTools())).append('\n');
        }
        if (!pm.getDeniedTools().isEmpty()) {
            sb.append("  Always deny:  ").append(String.join(", ", pm.getDeniedTools())).append('\n');
        }
        if (!pm.getAllDenials().isEmpty()) {
            sb.append("  Denial history:\n");
            pm.getAllDenials().forEach((tool, count) ->
                    sb.append("    ").append(tool).append(": ").append(count).append(" denial(s)\n"));
        }

        sb.append("\n  Usage: /permissions [auto|default|plan|deny]\n");
        return CommandResult.text(sb.toString());
    }
}
