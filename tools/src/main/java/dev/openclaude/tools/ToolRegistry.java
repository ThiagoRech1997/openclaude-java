package dev.openclaude.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

/**
 * Registry of all available tools. Provides lookup and schema generation.
 */
public final class ToolRegistry {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final List<Tool> tools = new ArrayList<>();

    public void register(Tool tool) {
        tools.add(tool);
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
}
