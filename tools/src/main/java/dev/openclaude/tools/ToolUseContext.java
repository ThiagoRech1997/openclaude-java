package dev.openclaude.tools;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Context passed to tools during execution.
 *
 * <p>{@code readFiles} is a shared, mutable set of absolute, normalized paths
 * that have been read (or written) by file-oriented tools during the session.
 * Write-oriented tools consult it to refuse overwriting files the agent has
 * not observed, guarding against unintended data loss.
 */
public record ToolUseContext(
        Path workingDirectory,
        boolean isAborted,
        Set<Path> readFiles
) {
    public ToolUseContext(Path workingDirectory) {
        this(workingDirectory, false, ConcurrentHashMap.newKeySet());
    }

    public ToolUseContext(Path workingDirectory, boolean isAborted) {
        this(workingDirectory, isAborted, ConcurrentHashMap.newKeySet());
    }
}
