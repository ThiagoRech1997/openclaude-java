package dev.openclaude.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Registry of all available tools. Provides lookup and schema generation.
 *
 * <p>Tools may be registered as <em>deferred</em>: they stay out of the API
 * tools array (saving prompt tokens) until {@link #activate} is called —
 * normally by the ToolSearch tool. The engine rebuilds the array every
 * iteration, so activation takes effect on the next LLM call.
 */
public final class ToolRegistry {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final List<Tool> tools = new ArrayList<>();
    private final Set<String> deferredNames = new HashSet<>();
    private final Set<String> activatedNames = ConcurrentHashMap.newKeySet();

    public void register(Tool tool) {
        tools.add(tool);
    }

    /** Register a tool whose schema is withheld from requests until activated. */
    public void registerDeferred(Tool tool) {
        tools.add(tool);
        deferredNames.add(tool.name());
    }

    /** Whether the tool is currently withheld from the API tools array. */
    public boolean isDeferred(String name) {
        return deferredNames.contains(name) && !activatedNames.contains(name);
    }

    /** Make a deferred tool's schema visible on subsequent requests. */
    public void activate(String name) {
        activatedNames.add(name);
    }

    /** Deferred tools not yet activated. */
    public List<Tool> deferredTools() {
        return tools.stream()
                .filter(t -> isDeferred(t.name()))
                .toList();
    }

    public Optional<Tool> findByName(String name) {
        return tools.stream()
                .filter(t -> t.name().equals(name))
                .findFirst();
    }

    public List<Tool> enabledTools() {
        return tools.stream()
                .filter(Tool::isEnabled)
                .toList();
    }

    /**
     * Generate the tools array for the Anthropic API request.
     * Each tool becomes: { name, description, input_schema }
     */
    public ArrayNode toApiToolsArray() {
        ArrayNode array = MAPPER.createArrayNode();
        for (Tool tool : enabledTools()) {
            if (isDeferred(tool.name())) continue;
            ObjectNode node = array.addObject();
            node.put("name", tool.name());
            node.put("description", tool.description());
            node.set("input_schema", tool.inputSchema());
        }
        return array;
    }

    public int size() {
        return tools.size();
    }

    public List<Tool> allTools() {
        return Collections.unmodifiableList(tools);
    }

    /**
     * Create a new registry containing only tools that match the filter.
     */
    public ToolRegistry filteredCopy(Predicate<Tool> filter) {
        ToolRegistry copy = new ToolRegistry();
        for (Tool tool : this.tools) {
            if (filter.test(tool)) {
                copy.register(tool);
                if (deferredNames.contains(tool.name())) {
                    copy.deferredNames.add(tool.name());
                }
                if (activatedNames.contains(tool.name())) {
                    copy.activatedNames.add(tool.name());
                }
            }
        }
        return copy;
    }
}
