package dev.openclaude.tools.filewrite;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.openclaude.tools.ToolResult;
import dev.openclaude.tools.ToolUseContext;
import dev.openclaude.tools.fileread.FileReadTool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FileWriteToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FileWriteTool writeTool;
    private FileReadTool readTool;
    private Set<Path> readFiles;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        writeTool = new FileWriteTool();
        readTool = new FileReadTool();
        readFiles = new HashSet<>();
    }

    private ToolUseContext context() {
        return new ToolUseContext(tempDir, false, readFiles);
    }

    private ObjectNode writeInput(Path p, String content) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("file_path", p.toAbsolutePath().toString());
        node.put("content", content);
        return node;
    }

    private ObjectNode readInput(Path p) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("file_path", p.toAbsolutePath().toString());
        return node;
    }

    @Nested
    @DisplayName("New files")
    class NewFiles {

        @Test
        void writesNewFileWithoutPriorRead() {
            Path f = tempDir.resolve("new.txt");

            ToolResult r = writeTool.execute(writeInput(f, "hello"), context());

            assertFalse(r.isError(), r.textContent());
            assertTrue(Files.exists(f));
        }

        @Test
        void createsParentDirectories() {
            Path f = tempDir.resolve("a/b/c/deep.txt");

            ToolResult r = writeTool.execute(writeInput(f, "deep"), context());

            assertFalse(r.isError(), r.textContent());
            assertTrue(Files.exists(f));
        }

        @Test
        void newFileIsMarkedAsReadForSubsequentWrites() throws IOException {
            Path f = tempDir.resolve("new.txt");

            ToolResult first = writeTool.execute(writeInput(f, "v1"), context());
            assertFalse(first.isError());

            ToolResult second = writeTool.execute(writeInput(f, "v2"), context());
            assertFalse(second.isError(), second.textContent());
            assertEquals("v2", Files.readString(f));
        }
    }

    @Nested
    @DisplayName("Existing files")
    class ExistingFiles {

        @Test
        void refusesOverwriteWithoutPriorRead() throws IOException {
            Path f = tempDir.resolve("existing.txt");
            Files.writeString(f, "original");

            ToolResult r = writeTool.execute(writeInput(f, "overwritten"), context());

            assertTrue(r.isError());
            assertTrue(r.textContent().contains("not been read"));
            assertEquals("original", Files.readString(f));
        }

        @Test
        void allowsOverwriteAfterPriorRead() throws IOException {
            Path f = tempDir.resolve("existing.txt");
            Files.writeString(f, "original");

            ToolResult read = readTool.execute(readInput(f), context());
            assertFalse(read.isError());

            ToolResult write = writeTool.execute(writeInput(f, "overwritten"), context());

            assertFalse(write.isError(), write.textContent());
            assertEquals("overwritten", Files.readString(f));
        }

        @Test
        void errorMessageIncludesPath() throws IOException {
            Path f = tempDir.resolve("existing.txt");
            Files.writeString(f, "original");

            ToolResult r = writeTool.execute(writeInput(f, "nope"), context());

            assertTrue(r.textContent().contains(f.toAbsolutePath().toString()));
        }
    }

    @Nested
    @DisplayName("Path normalization")
    class PathNormalization {

        @Test
        void relativePathReadThenAbsolutePathWriteBothWork() throws IOException {
            Path f = tempDir.resolve("file.txt");
            Files.writeString(f, "original");

            ObjectNode relRead = MAPPER.createObjectNode();
            relRead.put("file_path", "file.txt");
            ToolResult read = readTool.execute(relRead, context());
            assertFalse(read.isError());

            ToolResult write = writeTool.execute(writeInput(f, "new"), context());

            assertFalse(write.isError(), write.textContent());
            assertEquals("new", Files.readString(f));
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        void blankPathReturnsError() {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("file_path", "");
            node.put("content", "x");

            ToolResult r = writeTool.execute(node, context());

            assertTrue(r.isError());
        }
    }
}
