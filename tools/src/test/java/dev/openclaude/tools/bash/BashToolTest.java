package dev.openclaude.tools.bash;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.openclaude.tools.ToolResult;
import dev.openclaude.tools.ToolUseContext;
import dev.openclaude.tools.background.BackgroundProcessManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BashToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BackgroundProcessManager manager = new BackgroundProcessManager();
    private final BashTool tool = new BashTool(manager);
    private final ToolUseContext context = new ToolUseContext(Path.of("."));

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    @Test
    void simpleCommand_returnsOutput() {
        ToolResult result = tool.execute(input("echo hello", null), context);

        assertFalse(result.isError());
        assertTrue(result.textContent().contains("hello"));
    }

    @Test
    @Timeout(15)
    void hungCommand_isKilledAtTimeout() {
        long start = System.nanoTime();
        ToolResult result = tool.execute(input("sleep 30", 500L), context);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(result.isError());
        assertTrue(result.textContent().contains("timed out"),
                "expected timeout error, got: " + result.textContent());
        assertTrue(elapsedMs < 10_000,
                "timeout must fire near the configured 500ms, took " + elapsedMs + "ms");
    }

    @Test
    @Timeout(15)
    void timeout_preservesPartialOutput() {
        ToolResult result = tool.execute(input("echo partial-marker; sleep 30", 800L), context);

        assertTrue(result.isError());
        assertTrue(result.textContent().contains("partial-marker"),
                "partial output must be included: " + result.textContent());
    }

    private static ObjectNode input(String command, Long timeout) {
        ObjectNode node = MAPPER.createObjectNode()
                .put("command", command)
                .put("description", "test");
        if (timeout != null) {
            node.put("timeout", timeout);
        }
        return node;
    }
}
