package dev.openclaude.tools;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Context passed to tools during execution.
 *
 * <p>{@code workspace} carries the directory tools operate in; it is shared and
 * mutable so EnterWorktree/ExitWorktree can redirect the whole session into a
 * git worktree at runtime. {@link #workingDirectory()} always reflects the
 * current location.
 *
 * <p>{@code readFiles} is a shared, mutable set of absolute, normalized paths
 * that have been read (or written) by file-oriented tools during the session.
 * Write-oriented tools consult it to refuse overwriting files the agent has
 * not observed, guarding against unintended data loss.
 */
public record ToolUseContext(
        WorkspaceState workspace,
        boolean isAborted,
        Set<Path> readFiles
) {
    public ToolUseContext(Path workingDirectory) {
        this(new WorkspaceState(workingDirectory), false, ConcurrentHashMap.newKeySet());
    }

    public ToolUseContext(Path workingDirectory, boolean isAborted) {
        this(new WorkspaceState(workingDirectory), isAborted, ConcurrentHashMap.newKeySet());
    }

    public ToolUseContext(Path workingDirectory, boolean isAborted, Set<Path> readFiles) {
        this(new WorkspaceState(workingDirectory), isAborted, readFiles);
    }

    public Path workingDirectory() {
        return workspace.currentDirectory();
    }
}
