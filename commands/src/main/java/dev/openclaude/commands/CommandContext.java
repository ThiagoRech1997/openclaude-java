package dev.openclaude.commands;

import dev.openclaude.core.config.AppConfig;
import dev.openclaude.core.permissions.PermissionManager;
import dev.openclaude.core.session.SessionManager;
import dev.openclaude.tools.ToolRegistry;

import java.nio.file.Path;

/**
 * Context available to slash commands during execution.
 */
public record CommandContext(
        AppConfig config,
        ToolRegistry toolRegistry,
        PermissionManager permissions,
        SessionManager session,
        Path workingDirectory,
        int terminalWidth
) {}
