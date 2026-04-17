package dev.openclaude.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Owns the lifecycle of a single temporary git worktree used to isolate a sub-agent run.
 *
 * <p>Flow:
 * <ol>
 *   <li>{@link #create(Path)} forks a new branch from the parent repo's HEAD into a temp directory.</li>
 *   <li>The caller runs the sub-agent with the worktree as its working directory.</li>
 *   <li>{@link #finishAndMaybeCleanup(boolean)} inspects the worktree: if any uncommitted changes
 *       or new commits exist (or the run failed), the worktree is kept and its path+branch are
 *       returned; otherwise the worktree and branch are removed.</li>
 * </ol>
 *
 * <p>{@link #close()} is a safety net that runs cleanup if the caller never called
 * {@code finishAndMaybeCleanup}; it never throws.
 */
public final class WorktreeSession implements AutoCloseable {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final Path parentRepo;
    private final Path worktreePath;
    private final String branch;
    private final String startSha;
    private boolean finished = false;

    private WorktreeSession(Path parentRepo, Path worktreePath, String branch, String startSha) {
        this.parentRepo = parentRepo;
        this.worktreePath = worktreePath;
        this.branch = branch;
        this.startSha = startSha;
    }

    public static WorktreeSession create(Path parentRepo) throws IOException {
        GitResult check = runGit(parentRepo, "rev-parse", "--git-dir");
        if (check.exitCode != 0) {
            throw new IOException("isolation requires a git repository at " + parentRepo);
        }

        GitResult sha = runGit(parentRepo, "rev-parse", "HEAD");
        if (sha.exitCode != 0) {
            throw new IOException("failed to resolve HEAD in " + parentRepo + ": " + sha.output);
        }
        String startSha = sha.output.trim();

        String branch = "agent-" + LocalDateTime.now().format(TS) + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Path worktreeRoot = Path.of(System.getProperty("java.io.tmpdir"), "openclaude-worktrees");
        Files.createDirectories(worktreeRoot);
        Path worktreePath = worktreeRoot.resolve(branch);

        GitResult add = runGit(parentRepo, "worktree", "add", "-b", branch, worktreePath.toString(), "HEAD");
        if (add.exitCode != 0) {
            throw new IOException("git worktree add failed: " + add.output);
        }

        return new WorktreeSession(parentRepo, worktreePath, branch, startSha);
    }

    public Path path() {
        return worktreePath;
    }

    public String branch() {
        return branch;
    }

    public Result finishAndMaybeCleanup(boolean runFailed) {
        if (finished) {
            return new Result(true, worktreePath, branch);
        }
        finished = true;

        if (runFailed) {
            return new Result(true, worktreePath, branch);
        }

        boolean hasChanges;
        try {
            GitResult status = runGit(worktreePath, "status", "--porcelain");
            boolean dirty = status.exitCode == 0 && !status.output.isBlank();

            GitResult headSha = runGit(worktreePath, "rev-parse", "HEAD");
            boolean advanced = headSha.exitCode == 0 && !headSha.output.trim().equals(startSha);

            hasChanges = dirty || advanced;
        } catch (IOException e) {
            return new Result(true, worktreePath, branch);
        }

        if (hasChanges) {
            return new Result(true, worktreePath, branch);
        }

        cleanupQuiet();
        return new Result(false, worktreePath, branch);
    }

    @Override
    public void close() {
        if (finished) return;
        finished = true;
        cleanupQuiet();
    }

    private void cleanupQuiet() {
        try {
            runGit(parentRepo, "worktree", "remove", "--force", worktreePath.toString());
        } catch (IOException ignored) {
        }
        try {
            runGit(parentRepo, "branch", "-D", branch);
        } catch (IOException ignored) {
        }
    }

    private static GitResult runGit(Path cwd, String... args) throws IOException {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes());
        int exit;
        try {
            exit = p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while running git", e);
        }
        return new GitResult(exit, output);
    }

    public record Result(boolean kept, Path path, String branch) {}

    private record GitResult(int exitCode, String output) {}
}
