package dev.openclaude.tools.todo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.openclaude.tools.ToolResult;
import dev.openclaude.tools.ToolUseContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TodoWriteToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TodoStore store;
    private TodoWriteTool tool;
    private ToolUseContext context;

    @BeforeEach
    void setUp() {
        store = new TodoStore();
        tool = new TodoWriteTool(store);
        context = new ToolUseContext(Path.of("."), false);
    }

    private static ObjectNode todo(String content, String activeForm, String status) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("content", content);
        n.put("activeForm", activeForm);
        n.put("status", status);
        return n;
    }

    private static ObjectNode withTodos(ObjectNode... items) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode arr = root.putArray("todos");
        for (ObjectNode it : items) arr.add(it);
        return root;
    }

    @Nested
    @DisplayName("Metadata")
    class Metadata {

        @Test
        void nameAndReadOnlyFlag() {
            assertEquals("TodoWrite", tool.name());
            assertFalse(tool.isReadOnly());
            assertTrue(tool.isEnabled());
        }

        @Test
        void schemaDeclaresTodosRequired() {
            JsonNode schema = tool.inputSchema();
            assertEquals("object", schema.path("type").asText());
            assertTrue(schema.path("required").toString().contains("todos"));

            JsonNode items = schema.path("properties").path("todos").path("items");
            assertEquals("object", items.path("type").asText());
            String required = items.path("required").toString();
            assertTrue(required.contains("content"));
            assertTrue(required.contains("activeForm"));
            assertTrue(required.contains("status"));

            JsonNode statusEnum = items.path("properties").path("status").path("enum");
            assertTrue(statusEnum.isArray());
            String values = statusEnum.toString();
            assertTrue(values.contains("pending"));
            assertTrue(values.contains("in_progress"));
            assertTrue(values.contains("completed"));
        }
    }

    @Nested
    @DisplayName("execute")
    class Execute {

        @Test
        void persistsAllThreeStatusesAndRendersCheckboxes() {
            ObjectNode in = withTodos(
                    todo("Analyze", "Analyzing", TodoItem.COMPLETED),
                    todo("Implement", "Implementing", TodoItem.IN_PROGRESS),
                    todo("Test", "Testing", TodoItem.PENDING));

            ToolResult r = tool.execute(in, context);
            assertFalse(r.isError(), r.textContent());

            assertEquals(3, store.list().size());
            assertEquals("Analyze", store.list().get(0).content());
            assertEquals(TodoItem.IN_PROGRESS, store.list().get(1).status());

            String text = r.textContent();
            assertTrue(text.startsWith("Todos: 3 total"), "summary line must be first: " + text);
            assertTrue(text.contains("1 in_progress"));
            assertTrue(text.contains("1 pending"));
            assertTrue(text.contains("1 completed"));
            assertTrue(text.contains("☒"), "completed checkbox");
            assertTrue(text.contains("◐"), "in_progress checkbox");
            assertTrue(text.contains("☐"), "pending checkbox");
            assertTrue(text.contains("Implementing"), "in_progress row shows activeForm");
            assertTrue(text.contains("Test"), "pending row shows content");
        }

        @Test
        void emptyArrayClearsList() {
            store.set(java.util.List.of(new TodoItem("x", "doing x", TodoItem.PENDING)));
            ObjectNode in = withTodos();

            ToolResult r = tool.execute(in, context);
            assertFalse(r.isError());
            assertTrue(store.list().isEmpty());
            assertTrue(r.textContent().contains("0 total"));
        }

        @Test
        void missingTodosFieldIsError() {
            ToolResult r = tool.execute(MAPPER.createObjectNode(), context);
            assertTrue(r.isError());
            assertTrue(r.textContent().contains("Missing required field: todos"));
        }

        @Test
        void todosNotAnArrayIsError() {
            ObjectNode in = MAPPER.createObjectNode();
            in.put("todos", "not an array");
            ToolResult r = tool.execute(in, context);
            assertTrue(r.isError());
            assertTrue(r.textContent().contains("must be an array"));
        }

        @Test
        void missingContentIsError() {
            ObjectNode item = MAPPER.createObjectNode();
            item.put("activeForm", "Doing");
            item.put("status", TodoItem.PENDING);
            ObjectNode in = withTodos(item);

            ToolResult r = tool.execute(in, context);
            assertTrue(r.isError());
            assertTrue(r.textContent().contains("todos[0].content"));
        }

        @Test
        void missingActiveFormIsError() {
            ObjectNode item = MAPPER.createObjectNode();
            item.put("content", "Do thing");
            item.put("status", TodoItem.PENDING);
            ObjectNode in = withTodos(item);

            ToolResult r = tool.execute(in, context);
            assertTrue(r.isError());
            assertTrue(r.textContent().contains("todos[0].activeForm"));
        }

        @Test
        void blankContentIsError() {
            ObjectNode in = withTodos(todo("   ", "Doing", TodoItem.PENDING));
            ToolResult r = tool.execute(in, context);
            assertTrue(r.isError());
            assertTrue(r.textContent().contains("todos[0].content"));
        }

        @Test
        void invalidStatusIsError() {
            ObjectNode in = withTodos(todo("Do it", "Doing it", "done"));
            ToolResult r = tool.execute(in, context);
            assertTrue(r.isError());
            assertTrue(r.textContent().contains("invalid value 'done'"));
        }

        @Test
        void multipleInProgressIsError() {
            ObjectNode in = withTodos(
                    todo("A", "Doing A", TodoItem.IN_PROGRESS),
                    todo("B", "Doing B", TodoItem.IN_PROGRESS));

            ToolResult r = tool.execute(in, context);
            assertTrue(r.isError());
            assertTrue(r.textContent().contains("At most one todo"));
        }

        @Test
        void errorsAreAccumulatedNotFailFast() {
            ObjectNode missingContent = MAPPER.createObjectNode();
            missingContent.put("activeForm", "Doing");
            missingContent.put("status", TodoItem.PENDING);

            ObjectNode in = withTodos(
                    missingContent,
                    todo("B", "Doing B", "bogus"));

            ToolResult r = tool.execute(in, context);
            assertTrue(r.isError());
            String msg = r.textContent();
            assertTrue(msg.contains("todos[0].content"));
            assertTrue(msg.contains("todos[1].status"));
        }

        @Test
        void validationFailureLeavesStoreUnchanged() {
            store.set(java.util.List.of(new TodoItem("keep", "keeping", TodoItem.PENDING)));
            ObjectNode in = withTodos(todo("Do it", "Doing it", "done"));

            ToolResult r = tool.execute(in, context);
            assertTrue(r.isError());
            assertEquals(1, store.list().size());
            assertEquals("keep", store.list().get(0).content());
        }
    }
}
