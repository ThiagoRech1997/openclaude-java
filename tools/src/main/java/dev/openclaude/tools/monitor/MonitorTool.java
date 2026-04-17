package dev.openclaude.tools.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import dev.openclaude.tools.*;
import dev.openclaude.tools.background.BackgroundProcessManager;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Connects to a background process and retrieves new stdout lines since last check.
 * Each call drains the output buffer, so subsequent calls return only new lines.
 */
public class MonitorTool implements Tool {

    private static final JsonNode SCHEMA = SchemaBuilder.object()
            .stringProp("process_id",
                    "The background process ID (e.g. 'bg_1') returned by BashTool.", true)
            .stringProp("pattern",
                    "Optional regex pattern to filter output lines.", false)
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
        return "Stream events from a background process. Each stdout line is returned as output. "
                + "Use this to check on commands started with run_in_background in BashTool.";
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
        String processId = input.path("process_id").asText("");
        if (processId.isBlank()) {
            return ToolResult.error("process_id is required.");
        }

        var bgProcess = processManager.getProcess(processId);
        if (bgProcess == null) {
            return ToolResult.error("No background process found with ID: " + processId);
        }

        List<String> lines = processManager.drainOutput(processId);

        // Optional regex filtering
        String patternStr = input.path("pattern").asText(null);
        if (patternStr != null && !patternStr.isEmpty()) {
            Pattern regex = Pattern.compile(patternStr);
            lines = lines.stream()
                    .filter(line -> regex.matcher(line).find())
                    .collect(Collectors.toList());
        }

        // Build result
        StringBuilder result = new StringBuilder();
        for (String line : lines) {
            result.append(line).append('\n');
        }

        // Append process status
        if (bgProcess.isRunning()) {
            result.append("\n[Process ").append(processId).append(" is still running]");
        } else {
            try {
                result.append("\n[Process ").append(processId)
                        .append(" exited with code ").append(bgProcess.exitCode()).append("]");
            } catch (IllegalThreadStateException e) {
                result.append("\n[Process ").append(processId).append(" status unknown]");
            }
        }

        if (lines.isEmpty()) {
            return ToolResult.success("(no new output since last check)" + result);
        }

        return ToolResult.success(result.toString());
    }
}
