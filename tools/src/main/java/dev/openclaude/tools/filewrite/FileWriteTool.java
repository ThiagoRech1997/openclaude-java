package dev.openclaude.tools.filewrite;

import com.fasterxml.jackson.databind.JsonNode;
import dev.openclaude.tools.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes content to a file, creating parent directories if needed.
 */
public class FileWriteTool implements Tool {

    private static final JsonNode SCHEMA = SchemaBuilder.object()
            .stringProp("file_path", "The absolute path to the file to write.", true)
            .stringProp("content", "The content to write to the file.", true)
            .build();

    @Override
    public String name() {
        return "Write";
    }

    @Override
    public String description() {
        return "Writes content to a file, creating it and parent directories if they don't exist.";
    }

    @Override
    public JsonNode inputSchema() {
        return SCHEMA;
    }

    @Override
    public ToolResult execute(JsonNode input, ToolUseContext context) {
        String filePath = input.path("file_path").asText("");
        String content = input.path("content").asText("");

        if (filePath.isBlank()) {
            return ToolResult.error("file_path is required.");
        }

        Path path = Path.of(filePath);
        if (!path.isAbsolute()) {
            path = context.workingDirectory().resolve(path);
        }
        Path normalized = path.toAbsolutePath().normalize();

        if (Files.exists(path) && !context.readFiles().contains(normalized)) {
            return ToolResult.error(
                    "File has not been read in this session. Use the Read tool first before "
                            + "overwriting an existing file, to avoid unintended data loss: " + path);
        }

        try {
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            Files.writeString(path, content);
            context.readFiles().add(normalized);

            long bytes = content.getBytes().length;
            return ToolResult.success("Successfully wrote " + bytes + " bytes to " + path);

        } catch (IOException e) {
            return ToolResult.error("Failed to write file: " + e.getMessage());
        }
    }
}
