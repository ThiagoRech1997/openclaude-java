package dev.openclaude.tools.kill;

import com.fasterxml.jackson.databind.JsonNode;
import dev.openclaude.tools.*;
import dev.openclaude.tools.background.BackgroundProcessManager;
import dev.openclaude.tools.background.BackgroundProcessManager.BackgroundProcess;

/**
 * Terminates a background process started by BashTool.
 * Attempts a graceful shutdown first, falling back to forcible termination if needed.
 */
public class KillProcessTool implements Tool {

    private static final JsonNode SCHEMA = SchemaBuilder.object()
            .stringProp("process_id",
                    "The background process ID to terminate (e.g. 'bg_1').", true)
            .build();

    private final BackgroundProcessManager processManager;

    public KillProcessTool(BackgroundProcessManager processManager) {
        this.processManager = processManager;
    }

    @Override
    public String name() {
        return "KillProcess";
    }

    @Override
    public String description() {
        return "Terminate a background process by ID. Tries graceful shutdown first, then forcible kill.";
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
        String processId = input.path("process_id").asText("");
        if (processId.isBlank()) {
            return ToolResult.error("process_id is required.");
        }

        BackgroundProcess bp = processManager.getProcess(processId);
        if (bp == null) {
            return ToolResult.error("No background process found with ID: " + processId);
        }
        if (!bp.isRunning()) {
            processManager.removeProcess(processId);
            return ToolResult.success("Process " + processId + " had already exited (code "
                    + bp.exitCode() + "). Removed from registry.");
        }

        boolean killed = processManager.killProcess(processId);
        if (!killed) {
            return ToolResult.error("Failed to kill process " + processId + " (may have exited concurrently).");
        }
        processManager.removeProcess(processId);
        return ToolResult.success("Killed " + processId + ".");
    }
}
