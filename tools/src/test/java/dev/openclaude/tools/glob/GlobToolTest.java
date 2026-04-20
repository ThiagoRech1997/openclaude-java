package dev.openclaude.tools.glob;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.openclaude.tools.ToolResult;
import dev.openclaude.tools.ToolUseContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private GlobTool tool;

    @BeforeEach
    void setUp() {
        tool = new GlobTool();
    }

    private ToolUseContext ctx() {
        return new ToolUseContext(tempDir, false);
    }

    private ObjectNode input(String pattern) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("pattern", pattern);
        return n;
    }

    private void createFiles(int count) throws IOException {
        for (int i = 0; i < count; i++) {
            Files.writeString(tempDir.resolve("file" + i + ".txt"), "x");
        }
    }

    private long countLines(String text, String prefix) {
        return text.lines().filter(l -> l.startsWith(prefix)).count();
    }

    @Test
    void defaultLimitTruncatesAt500() throws IOException {
        createFiles(510);

        ToolResult r = tool.execute(input("*.txt"), ctx());

        assertFalse(r.isError());
        String out = r.textContent();
        assertTrue(out.contains("Found 500 file(s):"), out);
        assertTrue(out.contains("(results truncated at 500)"), out);
        assertTrue(countLines(out, tempDir.toString()) == 500, "expected 500 listed paths");
    }

    @Test
    void customLimitRespected() throws IOException {
        createFiles(50);

        ObjectNode in = input("*.txt");
        in.put("limit", 10);

        ToolResult r = tool.execute(in, ctx());

        assertFalse(r.isError());
        String out = r.textContent();
        assertTrue(out.contains("Found 10 file(s):"), out);
        assertTrue(out.contains("(results truncated at 10)"), out);
    }

    @Test
    void limitZeroMeansUnlimited() throws IOException {
        createFiles(520);

        ObjectNode in = input("*.txt");
        in.put("limit", 0);

        ToolResult r = tool.execute(in, ctx());

        assertFalse(r.isError());
        String out = r.textContent();
        assertTrue(out.contains("Found 520 file(s):"), out);
        assertFalse(out.contains("(results truncated"), out);
    }

    @Test
    void fewResultsProduceNoTruncationMessage() throws IOException {
        createFiles(5);

        ToolResult r = tool.execute(input("*.txt"), ctx());

        assertFalse(r.isError());
        String out = r.textContent();
        assertTrue(out.contains("Found 5 file(s):"), out);
        assertFalse(out.contains("(results truncated"), out);
    }
}
