package dev.openclaude.tools.grep;

import com.fasterxml.jackson.databind.JsonNode;
import dev.openclaude.tools.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Searches file contents using ripgrep (rg) or falls back to system grep.
 */
public class GrepTool implements Tool {

    private static final int MAX_RESULTS = 250;

    private static final JsonNode SCHEMA = SchemaBuilder.object()
            .stringProp("pattern", "Regular expression pattern to search for.", true)
            .stringProp("path", "File or directory to search in. Defaults to working directory.", false)
            .stringProp("glob", "Glob pattern to filter files (e.g., '*.java').", false)
            .enumProp("output_mode", "Output mode: 'content', 'files_with_matches', or 'count'.",
                    false, "content", "files_with_matches", "count")
            .boolProp("-i", "Case insensitive search.", false)
            .intProp("head_limit", "Limit output to first N results. Default 250.", false)
            .build();

    @Override
    public String name() {
        return "Grep";
    }

    @Override
    public String description() {
        return "Searches file contents using regex. Uses ripgrep if available, else grep.";
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
        String pattern = input.path("pattern").asText("");
        if (pattern.isBlank()) {
            return ToolResult.error("pattern is required.");
        }

        String pathStr = input.path("path").asText("");
        Path searchPath = pathStr.isBlank() ? context.workingDirectory() : Path.of(pathStr);
        String glob = input.path("glob").asText("");
        String outputMode = input.path("output_mode").asText("files_with_matches");
        boolean caseInsensitive = input.path("-i").asBoolean(false);
        int headLimit = input.has("head_limit") ? input.get("head_limit").asInt(MAX_RESULTS) : MAX_RESULTS;

        List<String> cmd = new ArrayList<>();

        // Try ripgrep first, fall back to grep
        if (isRipgrepAvailable()) {
            cmd.add("rg");
            cmd.add("--no-heading");
            if (caseInsensitive) cmd.add("-i");
            if (!glob.isBlank()) {
                cmd.add("--glob");
                cmd.add(glob);
            }
            switch (outputMode) {
                case "files_with_matches" -> {
                    cmd.add("-l");
                }
                case "count" -> {
                    cmd.add("-c");
                }
                default -> {
                    cmd.add("-n"); // line numbers for content mode
                }
            }
            cmd.add("--max-count");
            cmd.add(String.valueOf(headLimit * 2)); // rg max-count is per-file
            cmd.add(pattern);
            cmd.add(searchPath.toString());
        } else {
            cmd.add("grep");
            cmd.add("-r");
            cmd.add("-n");
            if (caseInsensitive) cmd.add("-i");
            if ("files_with_matches".equals(outputMode)) cmd.add("-l");
            if ("count".equals(outputMode)) cmd.add("-c");
            if (!glob.isBlank()) {
                cmd.add("--include");
                cmd.add(glob);
            }
            cmd.add(pattern);
            cmd.add(searchPath.toString());
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(context.workingDirectory().toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            int lineCount = 0;

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null && lineCount < headLimit) {
                    output.append(line).append('\n');
                    lineCount++;
                }
            }

            process.waitFor(30, TimeUnit.SECONDS);

            if (lineCount == 0) {
                return ToolResult.success("No matches found");
            }

            if (lineCount >= headLimit) {
                output.append("\n(results limited to ").append(headLimit).append(" entries)\n");
            }

            return ToolResult.success(output.toString());

        } catch (Exception e) {
            return ToolResult.error("Grep failed: " + e.getMessage());
        }
    }

    private boolean isRipgrepAvailable() {
        try {
            Process p = new ProcessBuilder("rg", "--version")
                    .redirectErrorStream(true)
                    .start();
            p.waitFor(5, TimeUnit.SECONDS);
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
