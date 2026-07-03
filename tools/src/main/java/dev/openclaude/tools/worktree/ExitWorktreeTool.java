package dev.openclaude.tools.worktree;

import com.fasterxml.jackson.databind.JsonNode;
import dev.openclaude.tools.SchemaBuilder;
import dev.openclaude.tools.Tool;
import dev.openclaude.tools.ToolResult;
import dev.openclaude.tools.ToolUseContext;
import dev.openclaude.tools.WorkspaceState;

/**
 * Leaves the active worktree and restores the original working directory.
 * With {@code keep_changes} (default true) a worktree containing work is kept
 * and its branch reported; otherwise it is removed along with its branch.
 */
public class ExitWorktreeTool implements Tool {

    private static final JsonNode SCHEMA = SchemaBuilder.object()
            .boolProp("keep_changes",
                    "Keep the worktree and its branch if it contains changes (default true). "
                            + "false discards the worktree regardless of its contents.", false)
            .build();

    @Override
    public String name() {
        return "ExitWorktree";
    }

    @Override
    public String description() {
        return "Leaves the current git worktree and returns the session to the original "
                + "working directory. Keeps or discards the worktree's changes.";
    }

    @Override
    public JsonNode inputSchema() {
        return SCHEMA;
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public ToolResult execute(JsonNode input, ToolUseContext context) {
        WorkspaceState workspace = context.workspace();
        WorktreeSession session = workspace.activeWorktree();
        if (session == null) {
            return ToolResult.error("Not inside a worktree.");
        }

        boolean keepChanges = input.path("keep_changes").asBoolean(true);
        workspace.exitWorktree();

        if (!keepChanges) {
            session.close(); // removes worktree + branch unconditionally
            return ToolResult.success("Worktree discarded. Back at "
                    + workspace.currentDirectory() + ".");
        }

        WorktreeSession.Result result = session.finishAndMaybeCleanup(false);
        if (result.kept()) {
            return ToolResult.success("Worktree kept at " + result.path()
                    + " — changes live on branch '" + result.branch()
                    + "' (visible in the main repository via git).\n"
                    + "Back at " + workspace.currentDirectory() + ".");
        }
        return ToolResult.success("Worktree had no changes and was removed. Back at "
                + workspace.currentDirectory() + ".");
    }
}
