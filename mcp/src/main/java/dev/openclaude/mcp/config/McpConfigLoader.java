package dev.openclaude.mcp.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Loads MCP server configurations from JSON files.
 *
 * Config locations (merged in order):
 *   1. Project-local: .mcp.json in working directory
 *   2. User-level: ~/.claude/settings.json → mcpServers
 *
 * JSON format:
 * {
 *   "mcpServers": {
 *     "server-name": {
 *       "type": "stdio",
 *       "command": "npx",
 *       "args": ["-y", "@modelcontextprotocol/server-filesystem", "/path"],
 *       "env": { "KEY": "value" }
 *     }
 *   }
 * }
 */
public final class McpConfigLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpConfigLoader() {}

    /**
     * Load all MCP server configs from available config files.
     */
    public static Map<String, McpServerConfig> load(Path workingDirectory) {
        Map<String, McpServerConfig> configs = new LinkedHashMap<>();

        // 1. User-level settings
        Path userSettings = getUserSettingsPath();
        if (userSettings != null) {
            configs.putAll(loadFromFile(userSettings));
        }

        // 2. Project-local .mcp.json (overrides user settings)
        Path projectConfig = workingDirectory.resolve(".mcp.json");
        configs.putAll(loadFromFile(projectConfig));

        return configs;
    }

    /**
     * Load MCP servers from a single JSON file.
     */
    static Map<String, McpServerConfig> loadFromFile(Path path) {
        Map<String, McpServerConfig> result = new LinkedHashMap<>();

        if (!Files.exists(path)) return result;

        try {
            String content = Files.readString(path);
            JsonNode root = MAPPER.readTree(content);

            JsonNode servers = root.get("mcpServers");
            if (servers == null) return result;

            var fieldNames = servers.fieldNames();
            while (fieldNames.hasNext()) {
                String name = fieldNames.next();
                JsonNode serverNode = servers.get(name);

                String type = serverNode.path("type").asText("stdio");
                String command = serverNode.path("command").asText(null);
                String url = serverNode.path("url").asText(null);

                // Parse args
                List<String> args = new ArrayList<>();
                JsonNode argsNode = serverNode.get("args");
                if (argsNode != null && argsNode.isArray()) {
                    for (JsonNode arg : argsNode) {
                        args.add(arg.asText());
                    }
                }

                // Parse env
                Map<String, String> env = new HashMap<>();
                JsonNode envNode = serverNode.get("env");
                if (envNode != null && envNode.isObject()) {
                    var envFields = envNode.fieldNames();
                    while (envFields.hasNext()) {
                        String key = envFields.next();
                        env.put(key, envNode.get(key).asText());
                    }
                }

                // Parse headers
                Map<String, String> headers = new HashMap<>();
                JsonNode headersNode = serverNode.get("headers");
                if (headersNode != null && headersNode.isObject()) {
                    var headerFields = headersNode.fieldNames();
                    while (headerFields.hasNext()) {
                        String key = headerFields.next();
                        headers.put(key, headersNode.get(key).asText());
                    }
                }

                // Substitute env vars in command and args
                if (command != null) {
                    command = substituteEnvVars(command);
                }
                args = args.stream().map(McpConfigLoader::substituteEnvVars).toList();

                result.put(name, new McpServerConfig(type, command, args, env, url, headers));
            }
        } catch (IOException e) {
            // Silently skip unreadable configs
        }

        return result;
    }

    private static Path getUserSettingsPath() {
        String home = System.getProperty("user.home");
        if (home == null) return null;

        Path settingsPath = Path.of(home, ".claude", "settings.json");
        return Files.exists(settingsPath) ? settingsPath : null;
    }

    /**
     * Substitute ${ENV_VAR} patterns in strings with environment variable values.
     */
    static String substituteEnvVars(String input) {
        if (input == null) return null;

        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < input.length()) {
            if (i + 1 < input.length() && input.charAt(i) == '$' && input.charAt(i + 1) == '{') {
                int end = input.indexOf('}', i + 2);
                if (end != -1) {
                    String varName = input.substring(i + 2, end);
                    String value = System.getenv(varName);
                    result.append(value != null ? value : "");
                    i = end + 1;
                    continue;
                }
            }
            result.append(input.charAt(i));
            i++;
        }
        return result.toString();
    }
}
