package dev.openclaude.tools;

import java.nio.file.Path;

/**
 * Context passed to tools during execution.
 */
public record ToolUseContext(
        Path workingDirectory,
        boolean isAborted
) {
    public ToolUseContext(Path workingDirectory) {
        this(workingDirectory, false);
    }
}
