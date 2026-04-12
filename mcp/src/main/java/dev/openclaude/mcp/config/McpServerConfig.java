package dev.openclaude.mcp.config;

import java.util.List;
import java.util.Map;

/**
 * Configuration for a single MCP server.
 * Matches the JSON format: { type, command, args, env, url, headers }
 */
public record McpServerConfig(
        String type,       // "stdio", "sse", "http", "ws"
        String command,    // For stdio: command to run
        List<String> args, // For stdio: command arguments
        Map<String, String> env, // For stdio: extra env vars
        String url,        // For sse/http/ws: server URL
        Map<String, String> headers // For sse/http: extra headers
) {
    /**
     * Create a stdio config.
     */
    public static McpServerConfig stdio(String command, List<String> args, Map<String, String> env) {
        return new McpServerConfig("stdio", command, args, env, null, null);
    }

    /**
     * Create a config with just a type and URL (for remote servers).
     */
    public static McpServerConfig remote(String type, String url) {
        return new McpServerConfig(type, null, null, null, url, null);
    }
}
