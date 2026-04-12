package dev.openclaude.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Arrays;

/**
 * Fluent builder for JSON Schema objects used by tool input schemas.
 */
public final class SchemaBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ObjectNode root;
    private final ObjectNode properties;
    private final ArrayNode required;

    private SchemaBuilder() {
        root = MAPPER.createObjectNode();
        root.put("type", "object");
        properties = root.putObject("properties");
        required = root.putArray("required");
        root.put("additionalProperties", false);
    }

    public static SchemaBuilder object() {
        return new SchemaBuilder();
    }

    public SchemaBuilder stringProp(String name, String description, boolean isRequired) {
        ObjectNode prop = properties.putObject(name);
        prop.put("type", "string");
        prop.put("description", description);
        if (isRequired) required.add(name);
        return this;
    }

    public SchemaBuilder intProp(String name, String description, boolean isRequired) {
        ObjectNode prop = properties.putObject(name);
        prop.put("type", "integer");
        prop.put("description", description);
        if (isRequired) required.add(name);
        return this;
    }

    public SchemaBuilder boolProp(String name, String description, boolean isRequired) {
        ObjectNode prop = properties.putObject(name);
        prop.put("type", "boolean");
        prop.put("description", description);
        if (isRequired) required.add(name);
        return this;
    }

    public SchemaBuilder enumProp(String name, String description, boolean isRequired, String... values) {
        ObjectNode prop = properties.putObject(name);
        prop.put("type", "string");
        prop.put("description", description);
        ArrayNode enumArr = prop.putArray("enum");
        for (String v : values) enumArr.add(v);
        if (isRequired) required.add(name);
        return this;
    }

    public JsonNode build() {
        return root;
    }
}
