package dev.openclaude.tools.fileread;

import com.fasterxml.jackson.databind.JsonNode;
import dev.openclaude.tools.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads a file and returns its contents with line numbers.
 */
public class FileReadTool implements Tool {

    private static final int DEFAULT_MAX_LINES = 2000;

    private static final JsonNode SCHEMA = SchemaBuilder.object()
            .stringProp("file_path", "The absolute path to the file to read.", true)
            .intProp("offset", "Line number to start reading from (0-based).", false)
            .intProp("limit", "Maximum number of lines to read.", false)
            .build();

    @Override
    public String name() {
        return "Read";
    }

    @Override
    public String description() {
        return "Reads a file from the filesystem and returns its contents with line numbers.";
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
        String filePath = input.path("file_path").asText("");
        if (filePath.isBlank()) {
            return ToolResult.error("file_path is required.");
        }

        Path path = resolvePath(filePath, context.workingDirectory());

        if (!Files.exists(path)) {
            return ToolResult.error("File does not exist: " + path);
        }

        if (Files.isDirectory(path)) {
            return ToolResult.error("Path is a directory, not a file: " + path
                    + ". Use Bash with 'ls' to list directory contents.");
        }

        int offset = input.has("offset") ? input.get("offset").asInt(0) : 0;
        int limit = input.has("limit") ? input.get("limit").asInt(DEFAULT_MAX_LINES) : DEFAULT_MAX_LINES;

        try {
            List<String> allLines = Files.readAllLines(path);
            int totalLines = allLines.size();

            if (offset >= totalLines) {
                return ToolResult.error("Offset " + offset + " exceeds file length of " + totalLines + " lines.");
            }

            int end = Math.min(offset + limit, totalLines);
            List<String> lines = allLines.subList(offset, end);

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                int lineNum = offset + i + 1; // 1-based
                sb.append(lineNum).append('\t').append(lines.get(i)).append('\n');
            }

            if (end < totalLines) {
                sb.append("\n... (").append(totalLines - end).append(" more lines not shown)\n");
            }

            return ToolResult.success(sb.toString());

        } catch (IOException e) {
            return ToolResult.error("Failed to read file: " + e.getMessage());
        }
    }

    private Path resolvePath(String filePath, Path workingDir) {
        Path p = Path.of(filePath);
        if (p.isAbsolute()) return p;
        return workingDir.resolve(p);
    }
}
