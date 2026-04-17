package dev.openclaude.tools.todo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.openclaude.tools.Tool;
import dev.openclaude.tools.ToolResult;
import dev.openclaude.tools.ToolUseContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Replace-all todo list manager. Every call sends the full list of todos,
 * which atomically replaces the session's current state.
 */
public class TodoWriteTool implements Tool {

    private static final String DESCRIPTION = """
            Use this tool to create and manage a structured task list for the current session.
            This helps track progress, organize complex tasks, and demonstrate thoroughness.

            ## When to use
            - Complex multi-step tasks (3+ distinct steps)
            - Non-trivial work that benefits from planning
            - When the user explicitly requests a todo list
            - After receiving new instructions — capture requirements as todos

            ## Contract
            Every call sends the FULL list of todos, which REPLACES the previous state.
            There is no "add" or "update-single" — to change one item, resend all items
            with the updated one modified.

            Each item has three fields:
            - content: imperative form ("Run tests", "Add login endpoint")
            - activeForm: present-continuous form ("Running tests", "Adding login endpoint")
            - status: one of "pending", "in_progress", "completed"

            ## Rules
            - Exactly one item may be "in_progress" at any time (may also be zero).
            - Mark an item "completed" IMMEDIATELY after finishing it — do not batch completions.
            - Never mark completed if tests fail, work is partial, or blockers remain.
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNode SCHEMA = buildSchema();

    private final TodoStore store;

    public TodoWriteTool(TodoStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "TodoWrite";
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public JsonNode inputSchema() {
        return SCHEMA;
    }

    @Override
    public ToolResult execute(JsonNode input, ToolUseContext context) {
        JsonNode todosNode = input.path("todos");
        if (todosNode.isMissingNode() || todosNode.isNull()) {
            return ToolResult.error("Missing required field: todos (array).");
        }
        if (!todosNode.isArray()) {
            return ToolResult.error("Field 'todos' must be an array.");
        }

        List<String> errors = new ArrayList<>();
        List<TodoItem> parsed = new ArrayList<>(todosNode.size());
        int inProgressCount = 0;

        for (int i = 0; i < todosNode.size(); i++) {
            JsonNode item = todosNode.get(i);
            if (item == null || item.isNull() || !item.isObject()) {
                errors.add("todos[" + i + "]: must be an object.");
                continue;
            }
            String content = textOrNull(item, "content");
            String activeForm = textOrNull(item, "activeForm");
            String status = textOrNull(item, "status");

            if (content == null || content.isBlank()) {
                errors.add("todos[" + i + "].content: required, non-blank string.");
            }
            if (activeForm == null || activeForm.isBlank()) {
                errors.add("todos[" + i + "].activeForm: required, non-blank string.");
            }
            if (status == null) {
                errors.add("todos[" + i + "].status: required, must be one of pending|in_progress|completed.");
            } else if (!TodoItem.isValidStatus(status)) {
                errors.add("todos[" + i + "].status: invalid value '" + status
                        + "'. Must be pending|in_progress|completed (case-sensitive).");
            } else if (TodoItem.IN_PROGRESS.equals(status)) {
                inProgressCount++;
            }

            if (content != null && activeForm != null && status != null
                    && !content.isBlank() && !activeForm.isBlank() && TodoItem.isValidStatus(status)) {
                parsed.add(new TodoItem(content, activeForm, status));
            }
        }

        if (inProgressCount > 1) {
            errors.add("At most one todo may have status=in_progress (found " + inProgressCount + ").");
        }

        if (!errors.isEmpty()) {
            return ToolResult.error("Invalid todos input:\n  - " + String.join("\n  - ", errors));
        }

        store.set(parsed);
        return ToolResult.success(format(parsed));
    }

    private static String textOrNull(JsonNode item, String field) {
        JsonNode n = item.get(field);
        return (n == null || n.isNull()) ? null : n.asText();
    }

    private static String format(List<TodoItem> items) {
        int pending = 0, inProgress = 0, completed = 0;
        for (TodoItem t : items) {
            switch (t.status()) {
                case TodoItem.PENDING -> pending++;
                case TodoItem.IN_PROGRESS -> inProgress++;
                case TodoItem.COMPLETED -> completed++;
                default -> { /* unreachable — validated above */ }
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Todos: ").append(items.size()).append(" total (")
                .append(inProgress).append(" in_progress, ")
                .append(pending).append(" pending, ")
                .append(completed).append(" completed)");
        if (items.isEmpty()) {
            sb.append(" — list is empty.");
            return sb.toString();
        }
        sb.append('\n');
        for (TodoItem t : items) {
            String box = switch (t.status()) {
                case TodoItem.COMPLETED -> "☒";
                case TodoItem.IN_PROGRESS -> "◐";
                default -> "☐";
            };
            String label = TodoItem.IN_PROGRESS.equals(t.status()) ? t.activeForm() : t.content();
            sb.append("  ").append(box).append(' ').append(label).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private static JsonNode buildSchema() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        ArrayNode required = root.putArray("required");
        required.add("todos");

        ObjectNode props = root.putObject("properties");
        ObjectNode todosProp = props.putObject("todos");
        todosProp.put("type", "array");
        todosProp.put("description",
                "Complete replacement list of todos. Send the full list every call; any item omitted is removed.");

        ObjectNode items = todosProp.putObject("items");
        items.put("type", "object");
        items.put("additionalProperties", false);
        ArrayNode itemRequired = items.putArray("required");
        itemRequired.add("content");
        itemRequired.add("activeForm");
        itemRequired.add("status");

        ObjectNode itemProps = items.putObject("properties");

        ObjectNode contentProp = itemProps.putObject("content");
        contentProp.put("type", "string");
        contentProp.put("description", "Imperative form of the task (e.g. 'Run tests').");

        ObjectNode activeFormProp = itemProps.putObject("activeForm");
        activeFormProp.put("type", "string");
        activeFormProp.put("description", "Present-continuous form shown while active (e.g. 'Running tests').");

        ObjectNode statusProp = itemProps.putObject("status");
        statusProp.put("type", "string");
        statusProp.put("description", "Current state of the todo.");
        ArrayNode statusEnum = statusProp.putArray("enum");
        statusEnum.add("pending");
        statusEnum.add("in_progress");
        statusEnum.add("completed");

        return root;
    }
}
