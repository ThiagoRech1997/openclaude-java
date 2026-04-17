package dev.openclaude.tools.kill;

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
class KillProcessToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BackgroundProcessManager manager;
    private KillProcessTool tool;
    private ToolUseContext context;

    @BeforeEach
    void setUp() {
        manager = new BackgroundProcessManager();
        tool = new KillProcessTool(manager);
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

    @Nested
    @DisplayName("Metadata")
    class Metadata {

        @Test
        void nameAndReadOnlyFlag() {
            assertEquals("KillProcess", tool.name());
            assertFalse(tool.isReadOnly());
        }
    }

    @Nested
    @DisplayName("execute")
    class Execute {

        @Test
        void killsRunningProcessAndRemovesFromRegistry() throws Exception {
            String id = startBash("sleep 30");
            var bp = manager.getProcess(id);
            assertTrue(bp.isRunning());

            ObjectNode in = MAPPER.createObjectNode().put("process_id", id);
            ToolResult r = tool.execute(in, context);
            assertFalse(r.isError());
            assertTrue(r.textContent().contains("Killed"));
            assertTrue(bp.process().waitFor(3, TimeUnit.SECONDS));
            assertNull(manager.getProcess(id), "killed process should be removed from registry");
        }

        @Test
        void unknownProcessIdReturnsError() {
            ObjectNode in = MAPPER.createObjectNode().put("process_id", "bg_nope");
            ToolResult r = tool.execute(in, context);
            assertTrue(r.isError());
            assertTrue(r.textContent().contains("No background process"));
        }

        @Test
        void blankProcessIdReturnsError() {
            ObjectNode in = MAPPER.createObjectNode().put("process_id", "");
            ToolResult r = tool.execute(in, context);
            assertTrue(r.isError());
        }

        @Test
        void alreadyExitedProcessIsCleanedUp() throws Exception {
            String id = startBash("true");
            var bp = manager.getProcess(id);
            assertTrue(bp.process().waitFor(3, TimeUnit.SECONDS));

            ObjectNode in = MAPPER.createObjectNode().put("process_id", id);
            ToolResult r = tool.execute(in, context);
            assertFalse(r.isError());
            assertTrue(r.textContent().contains("already exited"));
            assertNull(manager.getProcess(id));
        }
    }
}
