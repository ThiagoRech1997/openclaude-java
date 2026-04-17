package dev.openclaude.tools.notebook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.openclaude.tools.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Edits Jupyter notebooks (.ipynb) cell-by-cell.
 * Supports replace, insert, delete, and move operations while preserving
 * notebook metadata and untouched cells (including their outputs).
 */
public class NotebookEditTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final JsonNode SCHEMA = SchemaBuilder.object()
            .stringProp("notebook_path", "The absolute path to the .ipynb notebook to edit.", true)
            .enumProp("edit_mode",
                    "Operation to perform. Default is 'replace'.",
                    false, "replace", "insert", "delete", "move")
            .intProp("cell_index",
                    "0-based cell index. Required for replace/delete/move. For insert, the new cell is placed at this index (existing cells shift down); omit to append at the end.",
                    false)
            .intProp("new_index",
                    "Destination 0-based index. Required for move.",
                    false)
            .stringProp("new_source",
                    "New source for the cell. Required for replace and insert.",
                    false)
            .enumProp("cell_type",
                    "Cell type. Required for insert (default 'code'). Optional for replace (keeps existing type if omitted).",
                    false, "code", "markdown")
            .build();

    @Override
    public String name() {
        return "NotebookEdit";
    }

    @Override
    public String description() {
        return "Edits a Jupyter notebook (.ipynb) cell-by-cell. Supports replace, insert, delete, and move. Preserves notebook metadata and outputs of untouched cells; replacing a code cell's source clears its outputs and execution_count.";
    }

    @Override
    public JsonNode inputSchema() {
        return SCHEMA;
    }

    @Override
    public ToolResult execute(JsonNode input, ToolUseContext context) {
        String notebookPath = input.path("notebook_path").asText("");
        if (notebookPath.isBlank()) return ToolResult.error("notebook_path is required.");

        Path path = Path.of(notebookPath);
        if (!path.isAbsolute()) {
            path = context.workingDirectory().resolve(path);
        }

        if (!notebookPath.endsWith(".ipynb") && !path.getFileName().toString().endsWith(".ipynb")) {
            return ToolResult.error("notebook_path must end with .ipynb");
        }
        if (!Files.exists(path)) {
            return ToolResult.error("Notebook does not exist: " + path);
        }

        String editMode = input.path("edit_mode").asText("replace");

        try {
            JsonNode root = MAPPER.readTree(path.toFile());
            if (!root.isObject()) {
                return ToolResult.error("Invalid notebook: root must be a JSON object.");
            }
            JsonNode cellsNode = root.path("cells");
            if (!cellsNode.isArray()) {
                return ToolResult.error("Invalid notebook: missing 'cells' array.");
            }
            ArrayNode cells = (ArrayNode) cellsNode;

            String message = switch (editMode) {
                case "replace" -> applyReplace(cells, input);
                case "insert" -> applyInsert(cells, input);
                case "delete" -> applyDelete(cells, input);
                case "move" -> applyMove(cells, input);
                default -> null;
            };

            if (message == null) {
                return ToolResult.error("Invalid edit_mode: " + editMode);
            }
            if (message.startsWith("ERROR:")) {
                return ToolResult.error(message.substring("ERROR:".length()).trim());
            }

            MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), root);
            context.readFiles().add(path.toAbsolutePath().normalize());

            return ToolResult.success(message + " in " + path);
        } catch (IOException e) {
            return ToolResult.error("Failed to edit notebook: " + e.getMessage());
        }
    }

    private String applyReplace(ArrayNode cells, JsonNode input) {
        if (!input.has("cell_index")) return "ERROR: cell_index is required for replace.";
        int idx = input.path("cell_index").asInt();
        String err = checkIndex(idx, cells.size(), "cell_index");
        if (err != null) return err;
        if (!input.has("new_source")) return "ERROR: new_source is required for replace.";
        String newSource = input.path("new_source").asText();

        ObjectNode cell = (ObjectNode) cells.get(idx);
        String oldType = cell.path("cell_type").asText("code");
        String newType = input.hasNonNull("cell_type") ? input.path("cell_type").asText() : oldType;

        cell.put("cell_type", newType);
        cell.put("source", newSource);

        if ("code".equals(newType)) {
            cell.putArray("outputs");
            cell.putNull("execution_count");
        } else {
            cell.remove("outputs");
            cell.remove("execution_count");
        }
        return "Replaced cell " + idx;
    }

    private String applyInsert(ArrayNode cells, JsonNode input) {
        if (!input.has("new_source")) return "ERROR: new_source is required for insert.";
        String newSource = input.path("new_source").asText();
        String cellType = input.hasNonNull("cell_type") ? input.path("cell_type").asText() : "code";

        int size = cells.size();
        int idx = input.has("cell_index") ? input.path("cell_index").asInt() : size;
        if (idx < 0 || idx > size) {
            return "ERROR: cell_index out of range: " + idx + " (size=" + size + ")";
        }

        ObjectNode newCell = MAPPER.createObjectNode();
        newCell.put("cell_type", cellType);
        newCell.put("id", UUID.randomUUID().toString().substring(0, 8));
        newCell.putObject("metadata");
        newCell.put("source", newSource);
        if ("code".equals(cellType)) {
            newCell.putArray("outputs");
            newCell.putNull("execution_count");
        }

        cells.insert(idx, newCell);
        return "Inserted cell at index " + idx;
    }

    private String applyDelete(ArrayNode cells, JsonNode input) {
        if (!input.has("cell_index")) return "ERROR: cell_index is required for delete.";
        int idx = input.path("cell_index").asInt();
        String err = checkIndex(idx, cells.size(), "cell_index");
        if (err != null) return err;
        cells.remove(idx);
        return "Deleted cell " + idx;
    }

    private String applyMove(ArrayNode cells, JsonNode input) {
        if (!input.has("cell_index")) return "ERROR: cell_index is required for move.";
        if (!input.has("new_index")) return "ERROR: new_index is required for move.";
        int from = input.path("cell_index").asInt();
        int to = input.path("new_index").asInt();
        int size = cells.size();
        String err = checkIndex(from, size, "cell_index");
        if (err != null) return err;
        err = checkIndex(to, size, "new_index");
        if (err != null) return err;
        if (from == to) return "Moved cell " + from + " (no-op)";

        JsonNode cell = cells.remove(from);
        cells.insert(to, cell);
        return "Moved cell " + from + " to " + to;
    }

    private String checkIndex(int idx, int size, String fieldName) {
        if (size == 0) return "ERROR: notebook has no cells.";
        if (idx < 0 || idx >= size) {
            return "ERROR: " + fieldName + " out of range: " + idx + " (size=" + size + ")";
        }
        return null;
    }
}
