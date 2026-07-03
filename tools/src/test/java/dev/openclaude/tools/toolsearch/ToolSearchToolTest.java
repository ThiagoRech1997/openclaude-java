package dev.openclaude.tools.toolsearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.openclaude.tools.Tool;
import dev.openclaude.tools.ToolRegistry;
import dev.openclaude.tools.ToolResult;
import dev.openclaude.tools.ToolUseContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ToolSearchToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolRegistry registry;
    private ToolSearchTool toolSearch;
    private final ToolUseContext context = new ToolUseContext(Path.of("."));

    private static Tool fakeTool(String name, String description) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return description; }
            @Override public JsonNode inputSchema() { return MAPPER.createObjectNode().put("type", "object"); }
            @Override public ToolResult execute(JsonNode input, ToolUseContext ctx) {
                return ToolResult.success("ok");
            }
        };
    }

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
        registry.register(fakeTool("Core", "always available"));
        registry.registerDeferred(fakeTool("mcp__crm__search_clients", "Search clients in the CRM by name"));
        registry.registerDeferred(fakeTool("mcp__crm__create_client", "Create a client record in the CRM"));
        registry.registerDeferred(fakeTool("mcp__wiki__read_page", "Read a wiki page by slug"));
        toolSearch = new ToolSearchTool(registry);
        registry.register(toolSearch);
    }

    private ObjectNode input(String query) {
        return MAPPER.createObjectNode().put("query", query);
    }

    @Test
    void deferredTools_stayOutOfApiArray_untilActivated() {
        var names = new java.util.ArrayList<String>();
        registry.toApiToolsArray().forEach(n -> names.add(n.path("name").asText()));
        assertTrue(names.contains("Core"));
        assertTrue(names.contains("ToolSearch"));
        assertFalse(names.contains("mcp__crm__search_clients"), "deferred must be excluded");

        registry.activate("mcp__crm__search_clients");

        names.clear();
        registry.toApiToolsArray().forEach(n -> names.add(n.path("name").asText()));
        assertTrue(names.contains("mcp__crm__search_clients"), "activated must be included");
    }

    @Test
    void selectQuery_loadsSchemaAndActivates() {
        ToolResult result = toolSearch.execute(input("select:mcp__wiki__read_page"), context);

        assertFalse(result.isError());
        assertTrue(result.textContent().contains("mcp__wiki__read_page"));
        assertTrue(result.textContent().contains("input_schema"));
        assertFalse(registry.isDeferred("mcp__wiki__read_page"));
    }

    @Test
    void selectQuery_reportsUnknownNames() {
        ToolResult result = toolSearch.execute(
                input("select:mcp__wiki__read_page,DoesNotExist"), context);

        assertFalse(result.isError());
        assertTrue(result.textContent().contains("DoesNotExist"));
    }

    @Test
    void keywordQuery_matchesDeferredByDescription() {
        ToolResult result = toolSearch.execute(input("crm clients"), context);

        assertFalse(result.isError());
        assertTrue(result.textContent().contains("mcp__crm__search_clients"));
        assertTrue(result.textContent().contains("mcp__crm__create_client"));
        assertFalse(result.textContent().contains("mcp__wiki__read_page"));
    }

    @Test
    void keywordQuery_respectsMaxResults() {
        ObjectNode in = input("crm");
        in.put("max_results", 1);

        ToolResult result = toolSearch.execute(in, context);

        assertFalse(result.isError());
        assertTrue(result.textContent().contains("Loaded 1 tool(s)"));
    }

    @Test
    void noMatch_isError() {
        assertTrue(toolSearch.execute(input("kubernetes"), context).isError());
    }

    @Test
    void description_listsDeferredIndex_andShrinksAfterActivation() {
        String before = toolSearch.description();
        assertTrue(before.contains("mcp__crm__search_clients"));
        assertTrue(before.contains("Search clients"));

        toolSearch.execute(input("select:mcp__crm__search_clients"), context);

        assertFalse(toolSearch.description().contains("mcp__crm__search_clients — "),
                "activated tools must leave the deferred index");
    }

    @Test
    void filteredCopy_preservesDeferredState() {
        registry.activate("mcp__wiki__read_page");
        ToolRegistry copy = registry.filteredCopy(t -> true);

        assertTrue(copy.isDeferred("mcp__crm__search_clients"));
        assertFalse(copy.isDeferred("mcp__wiki__read_page"), "activation must carry over");
    }
}
