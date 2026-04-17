package dev.openclaude.tools.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.openclaude.tools.ToolResult;
import dev.openclaude.tools.ToolUseContext;
import dev.openclaude.tools.background.BackgroundProcessManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisabledOnOs(OS.WINDOWS)
class MonitorToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BackgroundProcessManager manager;
    private MonitorTool tool;
    private ToolUseContext context;

    @BeforeEach
    void setUp() {
        manager = new BackgroundProcessManager();
        tool = new MonitorTool(manager);
        context = new ToolUseContext(Path.of("."), false);
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    private String startBash(String command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
        pb.redirectErrorStream(true);
        return manager.startProcess(pb.start(), command);
    }

    private ObjectNode input() {
        return MAPPER.createObjectNode();
    }

    @Nested
    @DisplayName("action=list")
    class ListAction {

        @Test
        void emptyRegistryReturnsFriendlyMessage() {
            ObjectNode in = input().put("action", "list");
            ToolResult r = tool.execute(in, context);
            assertFalse(r.isError());
            assertEquals("(no background processes)", r.textContent());
        }

        @Test
        void listsIdAndCommand() throws Exception {
            String id = startBash("sleep 2");
            ObjectNode in = input().put("action", "list");
            ToolResult r = tool.execute(in, context);
            assertFalse(r.isError());
            String text = r.textContent();
            assertTrue(text.contains(id), "list output should contain id: " + text);
            assertTrue(text.contains("sleep 2"), "list output should contain command: " + text);
            assertTrue(text.contains("running"), "list output should show running status: " + text);
        }
    }

    @Nested
    @DisplayName("action=read")
    class ReadAction {

        @Test
        void missingProcessIdIsError() {
            ToolResult r = tool.execute(input().put("action", "read"), context);
            assertTrue(r.isError());
            assertTrue(r.textContent().contains("process_id"));
        }

        @Test
        void unknownIdIsError() {
            ObjectNode in = input().put("action", "read").put("process_id", "bg_missing");
            ToolResult r = tool.execute(in, context);
            assertTrue(r.isError());
        }

        @Test
        void defaultActionIsRead() throws Exception {
            String id = startBash("echo hi; sleep 10");
            ObjectNode in = input().put("process_id", id);
            ToolResult r = tool.execute(in, context);
            assertFalse(r.isError());
            // Don't assert on "hi" since reader thread races with this call.
            assertTrue(r.textContent().contains(id), "should reference process id");
        }

        @Test
        void autoCleanupAfterSecondPollOnExitedProcess() throws Exception {
            String id = startBash("echo done");
            var bp = manager.getProcess(id);
            assertTrue(bp.process().waitFor(3, TimeUnit.SECONDS));
            bp.readerThread().join(2_000);

            // First poll: drains output + reports exit status. Process stays in registry.
            ToolResult first = tool.execute(input().put("process_id", id), context);
            assertFalse(first.isError());
            assertTrue(first.textContent().contains("exited with code 0"));
            assertNotNull(manager.getProcess(id), "first poll must keep process registered");

            // Second poll: buffer empty + exited -> auto-removes.
            ToolResult second = tool.execute(input().put("process_id", id), context);
            assertFalse(second.isError());
            assertNull(manager.getProcess(id), "second poll should remove exited+drained process");

            // Third poll: now unknown.
            ToolResult third = tool.execute(input().put("process_id", id), context);
            assertTrue(third.isError());
        }

        @Test
        void patternFiltersOutput() throws Exception {
            String id = startBash("printf 'apple\\nbanana\\ncherry\\n'");
            var bp = manager.getProcess(id);
            assertTrue(bp.process().waitFor(3, TimeUnit.SECONDS));
            bp.readerThread().join(2_000);

            ObjectNode in = input().put("process_id", id).put("pattern", "^b");
            ToolResult r = tool.execute(in, context);
            assertFalse(r.isError());
            String text = r.textContent();
            assertTrue(text.contains("banana"), "should contain banana: " + text);
            assertFalse(text.contains("apple"), "should NOT contain apple: " + text);
            assertFalse(text.contains("cherry"), "should NOT contain cherry: " + text);
        }
    }

    @Nested
    @DisplayName("invalid action")
    class Invalid {

        @Test
        void unknownActionIsError() {
            ObjectNode in = input().put("action", "destroy").put("process_id", "bg_1");
            ToolResult r = tool.execute(in, context);
            assertTrue(r.isError());
            assertTrue(r.textContent().contains("Invalid action"));
        }
    }
}
