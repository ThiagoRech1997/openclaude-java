package dev.openclaude.tools.notebook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.openclaude.tools.ToolResult;
import dev.openclaude.tools.ToolUseContext;

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

class NotebookEditToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private NotebookEditTool tool;
    private Set<Path> readFiles;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        tool = new NotebookEditTool();
        readFiles = new HashSet<>();
    }

    private ToolUseContext context() {
        return new ToolUseContext(tempDir, false, readFiles);
    }

    private ObjectNode baseInput(Path nb) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("notebook_path", nb.toAbsolutePath().toString());
        return node;
    }

    private Path writeNotebook(String body) throws IOException {
        Path f = tempDir.resolve("nb.ipynb");
        Files.writeString(f, body);
        return f;
    }

    private JsonNode parse(Path nb) throws IOException {
        return MAPPER.readTree(nb.toFile());
    }

    private static final String SAMPLE_NOTEBOOK = """
            {
              "cells": [
                {
                  "cell_type": "code",
                  "id": "c1",
                  "metadata": {"tags": ["keep"]},
                  "execution_count": 7,
                  "source": "print('one')",
                  "outputs": [
                    {"output_type": "stream", "name": "stdout", "text": ["one\\n"]}
                  ]
                },
                {
                  "cell_type": "markdown",
                  "id": "c2",
                  "metadata": {},
                  "source": "# Heading"
                },
                {
                  "cell_type": "code",
                  "id": "c3",
                  "metadata": {},
                  "execution_count": null,
                  "source": "x = 2",
                  "outputs": []
                }
              ],
              "metadata": {
                "kernelspec": {"name": "python3", "display_name": "Python 3"}
              },
              "nbformat": 4,
              "nbformat_minor": 5
            }
            """;

    @Nested
    @DisplayName("replace")
    class Replace {

        @Test
        void replacesSourceAndClearsOutputsForCodeCell() throws IOException {
            Path nb = writeNotebook(SAMPLE_NOTEBOOK);

            ObjectNode in = baseInput(nb);
            in.put("edit_mode", "replace");
            in.put("cell_index", 0);
            in.put("new_source", "print('updated')");

            ToolResult r = tool.execute(in, context());

            assertFalse(r.isError(), r.textContent());
            JsonNode root = parse(nb);
            JsonNode cells = root.path("cells");
            assertEquals(3, cells.size());

            JsonNode c0 = cells.get(0);
            assertEquals("code", c0.path("cell_type").asText());
            assertEquals("print('updated')", c0.path("source").asText());
            assertTrue(c0.path("outputs").isArray());
            assertEquals(0, c0.path("outputs").size(), "outputs should be cleared");
            assertTrue(c0.path("execution_count").isNull(), "execution_count should be reset");
            assertEquals("c1", c0.path("id").asText(), "id must be preserved");
            assertEquals("keep", c0.path("metadata").path("tags").get(0).asText(),
                    "metadata must be preserved");

            assertEquals("# Heading", cells.get(1).path("source").asText(),
                    "other cells must be untouched");
            assertEquals("python3",
                    root.path("metadata").path("kernelspec").path("name").asText(),
                    "notebook metadata must be preserved");
        }

        @Test
        void canChangeCellTypeOnReplace() throws IOException {
            Path nb = writeNotebook(SAMPLE_NOTEBOOK);

            ObjectNode in = baseInput(nb);
            in.put("edit_mode", "replace");
            in.put("cell_index", 0);
            in.put("new_source", "just text");
            in.put("cell_type", "markdown");

            ToolResult r = tool.execute(in, context());

            assertFalse(r.isError(), r.textContent());
            JsonNode c0 = parse(nb).path("cells").get(0);
            assertEquals("markdown", c0.path("cell_type").asText());
            assertEquals("just text", c0.path("source").asText());
            assertTrue(c0.path("outputs").isMissingNode(),
                    "markdown cell must not carry outputs");
            assertTrue(c0.path("execution_count").isMissingNode(),
                    "markdown cell must not carry execution_count");
        }

        @Test
        void errorsOnMissingCellIndex() throws IOException {
            Path nb = writeNotebook(SAMPLE_NOTEBOOK);
            ObjectNode in = baseInput(nb);
            in.put("edit_mode", "replace");
            in.put("new_source", "x");

            ToolResult r = tool.execute(in, context());
            assertTrue(r.isError());
            assertTrue(r.textContent().contains("cell_index"));
        }

        @Test
        void errorsOnOutOfRangeIndex() throws IOException {
            Path nb = writeNotebook(SAMPLE_NOTEBOOK);
            ObjectNode in = baseInput(nb);
            in.put("edit_mode", "replace");
            in.put("cell_index", 99);
            in.put("new_source", "x");

            ToolResult r = tool.execute(in, context());
            assertTrue(r.isError());
            assertTrue(r.textContent().toLowerCase().contains("out of range"));
        }
    }

    @Nested
    @DisplayName("insert")
    class Insert {

        @Test
        void insertsMarkdownCellAtGivenIndex() throws IOException {
            Path nb = writeNotebook(SAMPLE_NOTEBOOK);
            ObjectNode in = baseInput(nb);
            in.put("edit_mode", "insert");
            in.put("cell_index", 1);
            in.put("cell_type", "markdown");
            in.put("new_source", "## Injected");

            ToolResult r = tool.execute(in, context());

            assertFalse(r.isError(), r.textContent());
            JsonNode cells = parse(nb).path("cells");
            assertEquals(4, cells.size());
            assertEquals("markdown", cells.get(1).path("cell_type").asText());
            assertEquals("## Injected", cells.get(1).path("source").asText());
            assertEquals("c2", cells.get(2).path("id").asText(),
                    "original cell 1 should have shifted to index 2");
        }

        @Test
        void appendsWhenCellIndexOmitted() throws IOException {
            Path nb = writeNotebook(SAMPLE_NOTEBOOK);
            ObjectNode in = baseInput(nb);
            in.put("edit_mode", "insert");
            in.put("new_source", "print('tail')");

            ToolResult r = tool.execute(in, context());

            assertFalse(r.isError(), r.textContent());
            JsonNode cells = parse(nb).path("cells");
            assertEquals(4, cells.size());
            JsonNode appended = cells.get(3);
            assertEquals("code", appended.path("cell_type").asText(),
                    "default cell_type must be code");
            assertEquals("print('tail')", appended.path("source").asText());
            assertTrue(appended.path("outputs").isArray());
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        void removesCellAtIndex() throws IOException {
            Path nb = writeNotebook(SAMPLE_NOTEBOOK);
            ObjectNode in = baseInput(nb);
            in.put("edit_mode", "delete");
            in.put("cell_index", 1);

            ToolResult r = tool.execute(in, context());

            assertFalse(r.isError(), r.textContent());
            JsonNode cells = parse(nb).path("cells");
            assertEquals(2, cells.size());
            assertEquals("c1", cells.get(0).path("id").asText());
            assertEquals("c3", cells.get(1).path("id").asText());
        }
    }

    @Nested
    @DisplayName("move")
    class Move {

        @Test
        void reordersCells() throws IOException {
            Path nb = writeNotebook(SAMPLE_NOTEBOOK);
            ObjectNode in = baseInput(nb);
            in.put("edit_mode", "move");
            in.put("cell_index", 0);
            in.put("new_index", 2);

            ToolResult r = tool.execute(in, context());

            assertFalse(r.isError(), r.textContent());
            JsonNode cells = parse(nb).path("cells");
            assertEquals(3, cells.size());
            assertEquals("c2", cells.get(0).path("id").asText());
            assertEquals("c3", cells.get(1).path("id").asText());
            assertEquals("c1", cells.get(2).path("id").asText());
        }

        @Test
        void errorsOnMissingNewIndex() throws IOException {
            Path nb = writeNotebook(SAMPLE_NOTEBOOK);
            ObjectNode in = baseInput(nb);
            in.put("edit_mode", "move");
            in.put("cell_index", 0);

            ToolResult r = tool.execute(in, context());
            assertTrue(r.isError());
            assertTrue(r.textContent().contains("new_index"));
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        void errorsWhenNotebookMissing() {
            Path missing = tempDir.resolve("missing.ipynb");
            ObjectNode in = baseInput(missing);
            in.put("edit_mode", "replace");
            in.put("cell_index", 0);
            in.put("new_source", "x");

            ToolResult r = tool.execute(in, context());
            assertTrue(r.isError());
            assertTrue(r.textContent().toLowerCase().contains("does not exist"));
        }

        @Test
        void errorsWhenNotIpynb() throws IOException {
            Path f = tempDir.resolve("notes.txt");
            Files.writeString(f, "not a notebook");

            ObjectNode in = baseInput(f);
            in.put("edit_mode", "replace");
            in.put("cell_index", 0);
            in.put("new_source", "x");

            ToolResult r = tool.execute(in, context());
            assertTrue(r.isError());
            assertTrue(r.textContent().contains(".ipynb"));
        }

        @Test
        void errorsWhenCellsArrayMissing() throws IOException {
            Path nb = writeNotebook("{\"metadata\": {}}");

            ObjectNode in = baseInput(nb);
            in.put("edit_mode", "replace");
            in.put("cell_index", 0);
            in.put("new_source", "x");

            ToolResult r = tool.execute(in, context());
            assertTrue(r.isError());
            assertTrue(r.textContent().contains("cells"));
        }

        @Test
        void errorsOnUnknownEditMode() throws IOException {
            Path nb = writeNotebook(SAMPLE_NOTEBOOK);
            ObjectNode in = baseInput(nb);
            in.put("edit_mode", "frobnicate");
            in.put("cell_index", 0);

            ToolResult r = tool.execute(in, context());
            assertTrue(r.isError());
            assertTrue(r.textContent().toLowerCase().contains("edit_mode"));
        }
    }
}
