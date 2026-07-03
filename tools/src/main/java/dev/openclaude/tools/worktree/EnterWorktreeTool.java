package dev.openclaude.tools.worktree;

import com.fasterxml.jackson.databind.JsonNode;
import dev.openclaude.tools.SchemaBuilder;
import dev.openclaude.tools.Tool;
import dev.openclaude.tools.ToolResult;
import dev.openclaude.tools.ToolUseContext;
import dev.openclaude.tools.WorkspaceState;

import java.io.IOException;

/**
 * Moves the session into a temporary git worktree: every subsequent tool call
 * operates there until ExitWorktree. Lets the model experiment (refactors,
 * risky changes) without touching the main working directory.
 */
public class EnterWorktreeTool implements Tool {

    private static final JsonNode SCHEMA = SchemaBuilder.object()
            .stringProp("branch", "Branch name to create for the worktree. Auto-generated if omitted.", false)
            .stringProp("base", "Ref to fork from (default: HEAD).", false)
            .build();

    @Override
    public String name() {
        return "EnterWorktree";
    }

    @Override
    public String description() {
        return "Creates a temporary git worktree and switches the session into it — "
                + "all subsequent tool calls run there until ExitWorktree. "
                + "Use for isolated experiments that must not dirty the main working directory.";
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
        if (workspace.activeWorktree() != null) {
            return ToolResult.error("Already inside worktree "
                    + workspace.activeWorktree().path() + ". Call ExitWorktree first.");
        }

        String branch = input.path("branch").asText(null);
        String base = input.path("base").asText(null);

        try {
            WorktreeSession session = WorktreeSession.create(
                    workspace.currentDirectory(), branch, base);
            workspace.enterWorktree(session);
            return ToolResult.success("Entered worktree at " + session.path()
                    + " on branch '" + session.branch() + "'.\n"
                    + "All tool calls now run there. Commits on this branch are visible "
                    + "in the main repository. Call ExitWorktree when done.");
        } catch (IOException | IllegalStateException e) {
            return ToolResult.error("Failed to create worktree: " + e.getMessage());
        }
    }
}
