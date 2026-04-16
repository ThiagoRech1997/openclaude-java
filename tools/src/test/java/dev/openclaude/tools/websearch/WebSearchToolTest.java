package dev.openclaude.tools.websearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.openclaude.tools.ToolResult;
import dev.openclaude.tools.ToolUseContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WebSearchToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private WebSearchTool tool;
    private ToolUseContext context;

    @BeforeEach
    void setUp() {
        tool = new WebSearchTool();
        context = new ToolUseContext(Path.of("."), false);
    }

    @Nested
    @DisplayName("Tool metadata")
    class Metadata {

        @Test
        void hasCorrectName() {
            assertEquals("WebSearch", tool.name());
        }

        @Test
        void isReadOnly() {
            assertTrue(tool.isReadOnly());
        }

        @Test
        void hasDescriptionMentioningSearch() {
            assertFalse(tool.description().isBlank());
            assertTrue(tool.description().toLowerCase().contains("search"));
        }

        @Test
        void schemaRequiresQuery() {
            JsonNode schema = tool.inputSchema();
            assertEquals("object", schema.path("type").asText());
            assertTrue(schema.path("properties").has("query"));
            assertTrue(schema.path("properties").has("num_results"));

            // query should be required
            JsonNode required = schema.path("required");
            assertTrue(required.isArray());
            boolean queryRequired = false;
            for (JsonNode r : required) {
                if ("query".equals(r.asText())) queryRequired = true;
            }
            assertTrue(queryRequired, "query should be in required array");
        }

        @Test
        void schemaHasNumResultsAsOptionalInteger() {
            JsonNode numResults = tool.inputSchema().path("properties").path("num_results");
            assertEquals("integer", numResults.path("type").asText());
        }
    }

    @Nested
    @DisplayName("Input validation")
    class InputValidation {

        @Test
        void rejectsEmptyQueryOrMissingApiKey() {
            ObjectNode input = MAPPER.createObjectNode();
            input.put("query", "");

            ToolResult result = tool.execute(input, context);
            // Without API key, returns API key error; with API key, returns query error
            // Either way, it must be an error
            assertTrue(result.isError());
        }

        @Test
        void rejectsMissingQuery() {
            ObjectNode input = MAPPER.createObjectNode();

            ToolResult result = tool.execute(input, context);
            assertTrue(result.isError());
        }
    }

    @Nested
    @DisplayName("Result parsing")
    class ResultParsing {

        @Test
        void parsesValidBraveResponse() throws IOException {
            String json = """
                    {
                      "web": {
                        "results": [
                          {
                            "title": "Java 17 Features",
                            "url": "https://example.com/java17",
                            "description": "Java 17 is the latest LTS release."
                          },
                          {
                            "title": "What's New in Java 17",
                            "url": "https://example.com/java17-new",
                            "description": "Explore sealed classes, pattern matching, and more."
                          }
                        ]
                      }
                    }
                    """;

            String result = tool.parseResults("java 17 features", json);

            assertTrue(result.contains("java 17 features"));
            assertTrue(result.contains("1. Java 17 Features"));
            assertTrue(result.contains("https://example.com/java17"));
            assertTrue(result.contains("Java 17 is the latest LTS release."));
            assertTrue(result.contains("2. What's New in Java 17"));
            assertTrue(result.contains("https://example.com/java17-new"));
        }

        @Test
        void handlesEmptyResults() throws IOException {
            String json = """
                    {
                      "web": {
                        "results": []
                      }
                    }
                    """;

            String result = tool.parseResults("nonexistent query", json);

            assertTrue(result.contains("No results found"));
        }

        @Test
        void handlesMissingWebField() throws IOException {
            String json = "{}";

            String result = tool.parseResults("test", json);

            assertTrue(result.contains("No results found"));
        }

        @Test
        void handlesResultsWithMissingFields() throws IOException {
            String json = """
                    {
                      "web": {
                        "results": [
                          {
                            "title": "Only Title"
                          }
                        ]
                      }
                    }
                    """;

            String result = tool.parseResults("test", json);

            assertTrue(result.contains("1. Only Title"));
        }
    }
}
