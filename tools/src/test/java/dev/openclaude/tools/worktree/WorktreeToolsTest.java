package dev.openclaude.tools.worktree;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.openclaude.tools.ToolResult;
import dev.openclaude.tools.ToolUseContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisabledOnOs(OS.WINDOWS)
@Timeout(30)
class WorktreeToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path repo;

    private ToolUseContext context;
    private final EnterWorktreeTool enter = new EnterWorktreeTool();
    private final ExitWorktreeTool exit = new ExitWorktreeTool();

    @BeforeEach
    void setUp() throws Exception {
        git("init");
        git("config", "user.email", "test@test");
        git("config", "user.name", "test");
        Files.writeString(repo.resolve("a.txt"), "hello\n");
        git("add", ".");
        git("commit", "-m", "init");
        context = new ToolUseContext(repo);
    }

    private void git(String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd).directory(repo.toFile())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor(), "git " + String.join(" ", args) + " failed: " + out);
    }

    private String gitOut(String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd).directory(repo.toFile())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        return out;
    }

    @Test
    void enter_switchesWorkingDirectory_exitWithoutChanges_restoresAndCleans() throws Exception {
        ToolResult entered = enter.execute(MAPPER.createObjectNode(), context);

        assertFalse(entered.isError(), entered.textContent());
        Path worktreeDir = context.workingDirectory();
        assertNotEquals(repo, worktreeDir, "cwd must move into the worktree");
        assertTrue(Files.exists(worktreeDir.resolve("a.txt")), "worktree has the repo contents");

        ToolResult exited = exit.execute(MAPPER.createObjectNode(), context);

        assertFalse(exited.isError());
        assertEquals(repo, context.workingDirectory(), "cwd must be restored");
        assertTrue(exited.textContent().contains("no changes"));
        assertFalse(Files.exists(worktreeDir), "clean worktree must be removed");
    }

    @Test
    void exitKeepingChanges_branchRemainsVisibleInMainRepo() throws Exception {
        ObjectNode in = MAPPER.createObjectNode().put("branch", "experiment-x");
        assertFalse(enter.execute(in, context).isError());

        Path worktreeDir = context.workingDirectory();
        Files.writeString(worktreeDir.resolve("b.txt"), "new work\n");

        ToolResult exited = exit.execute(MAPPER.createObjectNode(), context);

        assertFalse(exited.isError());
        assertTrue(exited.textContent().contains("experiment-x"));
        assertTrue(Files.exists(worktreeDir), "dirty worktree must be kept");
        assertTrue(gitOut("branch", "--list", "experiment-x").contains("experiment-x"),
                "branch must be visible in the main repo");
    }

    @Test
    void exitDiscarding_removesWorktreeEvenWithChanges() throws Exception {
        assertFalse(enter.execute(MAPPER.createObjectNode(), context).isError());
        Path worktreeDir = context.workingDirectory();
        Files.writeString(worktreeDir.resolve("scratch.txt"), "throwaway\n");

        ToolResult exited = exit.execute(
                MAPPER.createObjectNode().put("keep_changes", false), context);

        assertFalse(exited.isError());
        assertEquals(repo, context.workingDirectory());
        assertFalse(Files.exists(worktreeDir), "discarded worktree must be removed");
    }

    @Test
    void enterTwice_isError_andExitWithoutEnter_isError() {
        assertTrue(exit.execute(MAPPER.createObjectNode(), context).isError());

        assertFalse(enter.execute(MAPPER.createObjectNode(), context).isError());
        assertTrue(enter.execute(MAPPER.createObjectNode(), context).isError());

        assertFalse(exit.execute(MAPPER.createObjectNode(), context).isError());
    }

    @Test
    void enterOutsideGitRepo_isError(@TempDir Path notARepo) {
        ToolUseContext plain = new ToolUseContext(notARepo);
        ToolResult result = enter.execute(MAPPER.createObjectNode(), plain);
        assertTrue(result.isError());
        assertTrue(result.textContent().contains("git repository"));
    }
}
