package dev.openclaude.tools.toolsearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.openclaude.tools.SchemaBuilder;
import dev.openclaude.tools.Tool;
import dev.openclaude.tools.ToolRegistry;
import dev.openclaude.tools.ToolResult;
import dev.openclaude.tools.ToolUseContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Loads the full schemas of deferred tools on demand. Deferred tools appear
 * only as a lightweight name+summary index (kept in this tool's description);
 * once matched here they are activated in the {@link ToolRegistry} and become
 * callable on the next LLM request.
 *
 * <p>Query forms: {@code select:Name1,Name2} for exact selection, or free-text
 * keywords matched against tool names and descriptions.
 */
public class ToolSearchTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_MAX_RESULTS = 5;
    private static final int INDEX_SUMMARY_CHARS = 100;

    private static final JsonNode SCHEMA = SchemaBuilder.object()
            .stringProp("query",
                    "\"select:<name>,<name>\" for exact tool names, or keywords to search "
                            + "names and descriptions.", true)
            .intProp("max_results", "Maximum number of tools to load (default 5).", false)
            .build();

    private final ToolRegistry registry;

    public ToolSearchTool(ToolRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() {
        return "ToolSearch";
    }

    @Override
    public String description() {
        StringBuilder sb = new StringBuilder(
                "Load the full schemas of deferred tools so they can be called. "
                        + "Deferred tools are NOT callable until loaded here.");
        List<Tool> deferred = registry.deferredTools();
        if (!deferred.isEmpty()) {
            sb.append("\n\nDeferred tools:\n");
            for (Tool tool : deferred) {
                String summary = tool.description() == null ? "" : tool.description().strip();
                if (summary.length() > INDEX_SUMMARY_CHARS) {
                    summary = summary.substring(0, INDEX_SUMMARY_CHARS) + "…";
                }
                sb.append("- ").append(tool.name()).append(" — ").append(summary).append('\n');
            }
        }
        return sb.toString();
    }

    @Override
    public JsonNode inputSchema() {
        return SCHEMA;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(JsonNode input, ToolUseContext context) {
        String query = input.path("query").asText("").strip();
        if (query.isEmpty()) {
            return ToolResult.error("query is required.");
        }
        int maxResults = input.has("max_results")
                ? Math.max(1, input.get("max_results").asInt(DEFAULT_MAX_RESULTS))
                : DEFAULT_MAX_RESULTS;

        List<Tool> matches;
        List<String> notFound = new ArrayList<>();
        if (query.toLowerCase(Locale.ROOT).startsWith("select:")) {
            matches = selectByName(query.substring("select:".length()), notFound);
        } else {
            matches = searchByKeywords(query, maxResults);
        }

        if (matches.isEmpty()) {
            return ToolResult.error("No tools matched: " + query
                    + (notFound.isEmpty() ? "" : " (unknown: " + String.join(", ", notFound) + ")"));
        }

        StringBuilder sb = new StringBuilder("Loaded ").append(matches.size()).append(" tool(s):\n");
        for (Tool tool : matches) {
            registry.activate(tool.name());
            ObjectNode definition = MAPPER.createObjectNode();
            definition.put("name", tool.name());
            definition.put("description", tool.description());
            definition.set("input_schema", tool.inputSchema());
            sb.append('\n').append(definition.toPrettyString()).append('\n');
        }
        if (!notFound.isEmpty()) {
            sb.append("\nUnknown tools skipped: ").append(String.join(", ", notFound));
        }
        sb.append("\nThese tools are callable from the next step on.");
        return ToolResult.success(sb.toString());
    }

    private List<Tool> selectByName(String namesCsv, List<String> notFound) {
        List<Tool> matches = new ArrayList<>();
        for (String raw : namesCsv.split(",")) {
            String name = raw.strip();
            if (name.isEmpty()) continue;
            registry.findByName(name).ifPresentOrElse(matches::add, () -> notFound.add(name));
        }
        return matches;
    }

    private List<Tool> searchByKeywords(String query, int maxResults) {
        String[] terms = query.toLowerCase(Locale.ROOT).split("\\s+");
        record Scored(Tool tool, int score) {}

        List<Scored> scored = new ArrayList<>();
        for (Tool tool : registry.deferredTools()) {
            String haystack = (tool.name() + " " + tool.description()).toLowerCase(Locale.ROOT);
            int score = 0;
            for (String term : terms) {
                if (term.isBlank()) continue;
                int idx = 0;
                while ((idx = haystack.indexOf(term, idx)) >= 0) {
                    score++;
                    idx += term.length();
                }
            }
            if (score > 0) {
                scored.add(new Scored(tool, score));
            }
        }
        return scored.stream()
                .sorted(Comparator.comparingInt(Scored::score).reversed())
                .limit(maxResults)
                .map(Scored::tool)
                .toList();
    }
}
