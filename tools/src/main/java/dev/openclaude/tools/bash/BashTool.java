package dev.openclaude.tools.bash;

import com.fasterxml.jackson.databind.JsonNode;
import dev.openclaude.tools.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Executes bash commands via ProcessBuilder.
 * Streams stdout/stderr and enforces timeout.
 */
public class BashTool implements Tool {

    private static final long DEFAULT_TIMEOUT_MS = 120_000; // 2 minutes
    private static final long MAX_TIMEOUT_MS = 600_000; // 10 minutes

    private static final JsonNode SCHEMA = SchemaBuilder.object()
            .stringProp("command", "The bash command to execute.", true)
            .intProp("timeout", "Optional timeout in milliseconds (max 600000).", false)
            .stringProp("description", "Clear description of what this command does.", false)
            .build();

    @Override
    public String name() {
        return "Bash";
    }

    @Override
    public String description() {
        return "Executes a bash command and returns its output.";
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
        String command = input.path("command").asText("");
        if (command.isBlank()) {
            return ToolResult.error("Command is required.");
        }

        long timeout = input.has("timeout")
                ? Math.min(input.get("timeout").asLong(DEFAULT_TIMEOUT_MS), MAX_TIMEOUT_MS)
                : DEFAULT_TIMEOUT_MS;

        try {
            Path cwd = context.workingDirectory();
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

            ProcessBuilder pb;
            if (isWindows) {
                pb = new ProcessBuilder("cmd.exe", "/c", command);
            } else {
                pb = new ProcessBuilder("bash", "-c", command);
            }

            pb.directory(cwd.toFile());
            pb.redirectErrorStream(true);
            pb.environment().put("TERM", "dumb");

            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                    // Truncate very large outputs
                    if (output.length() > 512_000) {
                        output.append("\n... (output truncated at 512KB)\n");
                        break;
                    }
                }
            }

            boolean completed = process.waitFor(timeout, TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                return ToolResult.error("Command timed out after " + (timeout / 1000) + " seconds.\n"
                        + "Partial output:\n" + output);
            }

            int exitCode = process.exitValue();
            String result = output.toString();

            if (exitCode != 0) {
                return new ToolResult("Exit code " + exitCode + "\n" + result, true);
            }

            return ToolResult.success(result.isEmpty() ? "(no output)" : result);

        } catch (Exception e) {
            return ToolResult.error("Failed to execute command: " + e.getMessage());
        }
    }
}
