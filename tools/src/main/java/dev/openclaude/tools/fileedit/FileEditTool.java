package dev.openclaude.tools.fileedit;

import com.fasterxml.jackson.databind.JsonNode;
import dev.openclaude.tools.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Performs exact string replacement in files.
 * Matches old_string and replaces it with new_string.
 */
public class FileEditTool implements Tool {

    private static final JsonNode SCHEMA = SchemaBuilder.object()
            .stringProp("file_path", "The absolute path to the file to modify.", true)
            .stringProp("old_string", "The text to find and replace. Must be unique in the file.", true)
            .stringProp("new_string", "The replacement text.", true)
            .boolProp("replace_all", "If true, replace all occurrences (default false).", false)
            .build();

    @Override
    public String name() {
        return "Edit";
    }

    @Override
    public String description() {
        return "Performs exact string replacements in files. The old_string must be unique unless replace_all is true.";
    }

    @Override
    public JsonNode inputSchema() {
        return SCHEMA;
    }

    @Override
    public ToolResult execute(JsonNode input, ToolUseContext context) {
        String filePath = input.path("file_path").asText("");
        String oldString = input.path("old_string").asText("");
        String newString = input.path("new_string").asText("");
        boolean replaceAll = input.path("replace_all").asBoolean(false);

        if (filePath.isBlank()) return ToolResult.error("file_path is required.");
        if (oldString.isEmpty()) return ToolResult.error("old_string is required.");
        if (oldString.equals(newString)) return ToolResult.error("old_string and new_string must be different.");

        Path path = Path.of(filePath);
        if (!path.isAbsolute()) {
            path = context.workingDirectory().resolve(path);
        }

        if (!Files.exists(path)) {
            return ToolResult.error("File does not exist: " + path);
        }

        try {
            String content = Files.readString(path);

            if (!content.contains(oldString)) {
                // Try to give a helpful error
                String trimmed = oldString.trim();
                if (!trimmed.equals(oldString) && content.contains(trimmed)) {
                    return ToolResult.error(
                            "old_string not found exactly, but a trimmed version was found. "
                            + "Check for whitespace/indentation differences.");
                }
                return ToolResult.error("old_string not found in file. "
                        + "Make sure it matches exactly, including whitespace and indentation.");
            }

            if (!replaceAll) {
                int firstIdx = content.indexOf(oldString);
                int secondIdx = content.indexOf(oldString, firstIdx + 1);
                if (secondIdx != -1) {
                    return ToolResult.error(
                            "old_string appears multiple times in the file. "
                            + "Provide more context to make it unique, or use replace_all: true.");
                }
            }

            String updated;
            int count;
            if (replaceAll) {
                count = countOccurrences(content, oldString);
                updated = content.replace(oldString, newString);
            } else {
                count = 1;
                int idx = content.indexOf(oldString);
                updated = content.substring(0, idx) + newString + content.substring(idx + oldString.length());
            }

            Files.writeString(path, updated);
            context.readFiles().add(path.toAbsolutePath().normalize());

            return ToolResult.success("Successfully replaced " + count + " occurrence(s) in " + path);

        } catch (IOException e) {
            return ToolResult.error("Failed to edit file: " + e.getMessage());
        }
    }

    private int countOccurrences(String text, String search) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(search, idx)) != -1) {
            count++;
            idx += search.length();
        }
        return count;
    }
}
