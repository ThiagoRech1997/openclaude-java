package dev.openclaude.tools.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import dev.openclaude.tools.*;
import dev.openclaude.tools.background.BackgroundProcessManager;
import dev.openclaude.tools.background.BackgroundProcessManager.BackgroundProcess;

import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Observes background processes started by BashTool.
 * <ul>
 *   <li>{@code action="read"} (default): drains new stdout lines for a given process_id.</li>
 *   <li>{@code action="list"}: returns a status table of all tracked processes.</li>
 * </ul>
 * When a read call sees an already-drained exited process, that process is auto-removed
 * from the registry to prevent unbounded growth over long sessions.
 */
public class MonitorTool implements Tool {

    private static final JsonNode SCHEMA = SchemaBuilder.object()
            .enumProp("action",
                    "What to do: 'read' (default) drains new output from one process; 'list' summarizes all tracked processes.",
                    false, "read", "list")
            .stringProp("process_id",
                    "The background process ID (e.g. 'bg_1'). Required for action='read'; ignored for action='list'.", false)
            .stringProp("pattern",
                    "Optional regex pattern to filter output lines. Only applies to action='read'.", false)
            .build();

    private final BackgroundProcessManager processManager;

    public MonitorTool(BackgroundProcessManager processManager) {
        this.processManager = processManager;
    }

    @Override
    public String name() {
        return "Monitor";
    }

    @Override
    public String description() {
        return "Observe background processes. Use action='read' with a process_id to stream new output, "
                + "or action='list' to see all tracked background processes and their status.";
    }

    @Override
    public JsonNode inputSchema() {
        return SCHEMA;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(JsonNode input, ToolUseContext context) {
        String action = input.path("action").asText("read");
        return switch (action) {
            case "list" -> executeList();
            case "read" -> executeRead(input);
            default -> ToolResult.error("Invalid action: '" + action + "'. Expected 'read' or 'list'.");
        };
    }

    private ToolResult executeList() {
        Collection<BackgroundProcess> all = processManager.listProcesses();
        if (all.isEmpty()) {
            return ToolResult.success("(no background processes)");
        }
        StringBuilder sb = new StringBuilder();
        for (BackgroundProcess bp : all) {
            sb.append('[').append(bp.id()).append("] ");
            if (bp.isRunning()) {
                sb.append("running          ");
            } else {
                try {
                    sb.append(String.format("exited (code %d)", bp.exitCode()));
                    if (bp.exitCode() >= 0 && bp.exitCode() <= 9) sb.append("   ");
                    else sb.append("  ");
                } catch (IllegalThreadStateException e) {
                    sb.append("status unknown   ");
                }
            }
            sb.append(" — ").append(bp.command()).append('\n');
        }
        return ToolResult.success(sb.toString().stripTrailing());
    }

    private ToolResult executeRead(JsonNode input) {
        String processId = input.path("process_id").asText("");
        if (processId.isBlank()) {
            return ToolResult.error("process_id is required for action='read'.");
        }

        BackgroundProcess bgProcess = processManager.getProcess(processId);
        if (bgProcess == null) {
            return ToolResult.error("No background process found with ID: " + processId);
        }

        List<String> lines = processManager.drainOutput(processId);
        boolean drainedAnything = !lines.isEmpty();

        String patternStr = input.path("pattern").asText(null);
        if (patternStr != null && !patternStr.isEmpty()) {
            Pattern regex = Pattern.compile(patternStr);
            lines = lines.stream()
                    .filter(line -> regex.matcher(line).find())
                    .collect(Collectors.toList());
        }

        StringBuilder result = new StringBuilder();
        for (String line : lines) {
            result.append(line).append('\n');
        }

        boolean exited = !bgProcess.isRunning();
        if (!exited) {
            result.append("\n[Process ").append(processId).append(" is still running]");
        } else {
            try {
                result.append("\n[Process ").append(processId)
                        .append(" exited with code ").append(bgProcess.exitCode()).append("]");
            } catch (IllegalThreadStateException e) {
                result.append("\n[Process ").append(processId).append(" status unknown]");
            }
        }

        // Two-stage auto-cleanup: remove only when the process has exited AND there was nothing
        // new to drain on this call (so a prior call already surfaced the exit status). Uses the
        // pre-filter drain count so a non-matching pattern doesn't mimic "empty buffer".
        if (exited && !drainedAnything) {
            processManager.removeProcess(processId);
        }

        if (lines.isEmpty()) {
            return ToolResult.success("(no new output since last check)" + result);
        }
        return ToolResult.success(result.toString());
    }
}
