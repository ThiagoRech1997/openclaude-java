package dev.openclaude.tools;

import dev.openclaude.tools.worktree.WorktreeSession;

import java.nio.file.Path;

/**
 * Mutable per-engine workspace: the directory tools operate in. Normally the
 * original working directory, but EnterWorktree/ExitWorktree can move it into
 * a temporary git worktree and back at runtime.
 */
public final class WorkspaceState {

    private final Path originalDirectory;
    private volatile Path currentDirectory;
    private volatile WorktreeSession activeWorktree;

    public WorkspaceState(Path directory) {
        this.originalDirectory = directory;
        this.currentDirectory = directory;
    }

    public Path currentDirectory() {
        return currentDirectory;
    }

    public Path originalDirectory() {
        return originalDirectory;
    }

    public WorktreeSession activeWorktree() {
        return activeWorktree;
    }

    public synchronized void enterWorktree(WorktreeSession session) {
        if (activeWorktree != null) {
            throw new IllegalStateException("Already inside worktree " + activeWorktree.path());
        }
        activeWorktree = session;
        currentDirectory = session.path();
    }

    /** Leaves the worktree and restores the original directory; returns the session. */
    public synchronized WorktreeSession exitWorktree() {
        WorktreeSession session = activeWorktree;
        activeWorktree = null;
        currentDirectory = originalDirectory;
        return session;
    }
}
