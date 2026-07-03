package dev.openclaude.tools.grep;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.openclaude.tools.ToolResult;
import dev.openclaude.tools.ToolUseContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class GrepToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GrepTool tool = new GrepTool();

    @TempDir
    Path tempDir;

    @Test
    void patternStartingWithDash_isNotParsedAsFlag() throws Exception {
        Files.writeString(tempDir.resolve("data.txt"), "value: -123\nother line\n");

        ObjectNode input = MAPPER.createObjectNode()
                .put("pattern", "-[0-9]+")
                .put("path", tempDir.toString())
                .put("output_mode", "content");

        ToolResult result = tool.execute(input, new ToolUseContext(tempDir));

        assertFalse(result.isError(), "dash-leading pattern must not be read as a flag: "
                + result.textContent());
        assertTrue(result.textContent().contains("-123"));
    }

    @Test
    void countMode_isNotCappedByHeadLimit() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            sb.append("match line ").append(i).append('\n');
        }
        Files.writeString(tempDir.resolve("many.txt"), sb.toString());

        ObjectNode input = MAPPER.createObjectNode()
                .put("pattern", "match")
                .put("path", tempDir.toString())
                .put("output_mode", "count");

        ToolResult result = tool.execute(input, new ToolUseContext(tempDir));

        assertFalse(result.isError());
        // Before the fix, --max-count capped per-file counts at head_limit*2 (500)
        assertTrue(result.textContent().contains("600"),
                "count must report all 600 matches: " + result.textContent());
    }
}
