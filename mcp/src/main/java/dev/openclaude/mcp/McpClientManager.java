package dev.openclaude.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.openclaude.mcp.config.McpServerConfig;
import dev.openclaude.mcp.transport.StdioTransport;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages MCP server connections, initialization, and tool discovery.
 *
 * Flow:
 *   1. Load server configs from JSON
 *   2. Connect to each server (spawn process / connect HTTP)
 *   3. Initialize (send initialize request)
 *   4. List tools from each server
 *   5. Expose tools to the agent via McpToolBridge
 */
public class McpClientManager implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long DEFAULT_TIMEOUT_MS = 30_000;

    private final Map<String, McpServer> servers = new ConcurrentHashMap<>();

    /**
     * Connect to all configured MCP servers.
     *
     * @param configs map of server name → config
     * @return list of connected server states
     */
    public List<McpServer> connectAll(Map<String, McpServerConfig> configs) {
        List<McpServer> results = new ArrayList<>();

        for (var entry : configs.entrySet()) {
            String name = entry.getKey();
            McpServerConfig config = entry.getValue();

            McpServer server = connect(name, config);
            servers.put(name, server);
            results.add(server);
        }

        return results;
    }

    /**
     * Connect to a single MCP server.
     */
    public McpServer connect(String name, McpServerConfig config) {
        try {
            McpTransportClient transport = createTransport(name, config);

            // Initialize the MCP protocol
            McpServer.ServerInfo serverInfo = initialize(transport);

            // List available tools
            List<McpServer.McpTool> tools = listTools(transport);

            McpServer.Connected connected = new McpServer.Connected(name, transport, serverInfo, tools);
            servers.put(name, connected);
            return connected;

        } catch (Exception e) {
            McpServer.Failed failed = new McpServer.Failed(name, e.getMessage());
            servers.put(name, failed);
            return failed;
        }
    }

    /**
     * Get all connected servers.
     */
    public List<McpServer.Connected> connectedServers() {
        List<McpServer.Connected> result = new ArrayList<>();
        for (McpServer server : servers.values()) {
            if (server instanceof McpServer.Connected c) {
                result.add(c);
            }
        }
        return result;
    }

    /**
     * Get all servers (any state).
     */
    public Collection<McpServer> allServers() {
        return Collections.unmodifiableCollection(servers.values());
    }

    /**
     * Get all tools from all connected servers.
     * Tool names are prefixed: mcp__<serverName>__<toolName>
     */
    public List<McpServer.McpTool> allTools() {
        List<McpServer.McpTool> tools = new ArrayList<>();
        for (McpServer.Connected server : connectedServers()) {
            for (McpServer.McpTool tool : server.tools()) {
                String qualifiedName = "mcp__" + normalizeName(server.name()) + "__" + normalizeName(tool.name());
                tools.add(new McpServer.McpTool(qualifiedName, tool.description(), tool.inputSchema()));
            }
        }
        return tools;
    }

    /**
     * Call a tool on a connected MCP server.
     *
     * @param qualifiedToolName fully qualified name (mcp__server__tool)
     * @param arguments tool arguments as JSON
     * @return the tool result content
     */
    public String callTool(String qualifiedToolName, JsonNode arguments) throws McpException {
        // Parse qualified name: mcp__<server>__<tool>
        String[] parts = qualifiedToolName.split("__", 3);
        if (parts.length != 3 || !"mcp".equals(parts[0])) {
            throw new McpException("Invalid MCP tool name: " + qualifiedToolName);
        }

        String serverName = parts[1];
        String toolName = parts[2];

        // Find the connected server
        McpServer.Connected server = findServer(serverName);
        if (server == null) {
            throw new McpException("MCP server not connected: " + serverName);
        }

        // Build tools/call request
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", arguments);

        JsonNode result = server.transport().request("tools/call", params, DEFAULT_TIMEOUT_MS);

        // Extract content from result
        if (result == null) return "";

        boolean isError = result.path("isError").asBoolean(false);
        JsonNode content = result.get("content");
        if (content == null) return "";

        StringBuilder sb = new StringBuilder();
        if (content.isArray()) {
            for (JsonNode block : content) {
                String type = block.path("type").asText("text");
                if ("text".equals(type)) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(block.path("text").asText());
                }
            }
        } else {
            sb.append(content.asText());
        }

        if (isError) {
            throw new McpException(sb.toString());
        }

        return sb.toString();
    }

    @Override
    public void close() {
        for (McpServer server : servers.values()) {
            if (server instanceof McpServer.Connected c) {
                try {
                    c.transport().close();
                } catch (Exception ignored) {}
            }
        }
        servers.clear();
    }

    // --- Internal ---

    private McpTransportClient createTransport(String name, McpServerConfig config) throws McpException {
        return switch (config.type()) {
            case "stdio" -> new StdioTransport(
                    config.command(),
                    config.args() != null ? config.args() : List.of(),
                    config.env(),
                    null
            );
            default -> throw new McpException(
                    "Unsupported MCP transport type: " + config.type()
                    + ". Currently supported: stdio");
        };
    }

    /**
     * MCP protocol initialization handshake.
     */
    private McpServer.ServerInfo initialize(McpTransportClient transport) throws McpException {
        ObjectNode params = MAPPER.createObjectNode();

        // Client capabilities
        ObjectNode capabilities = params.putObject("capabilities");
        // We don't declare any special capabilities for now

        // Client info
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "openclaude-java");
        clientInfo.put("version", "0.1.0");

        // Protocol version
        params.put("protocolVersion", "2024-11-05");

        JsonNode result = transport.request("initialize", params, DEFAULT_TIMEOUT_MS);

        String serverName = result.path("serverInfo").path("name").asText("unknown");
        String serverVersion = result.path("serverInfo").path("version").asText("unknown");
        String instructions = result.path("instructions").asText("");

        // Send initialized notification
        transport.notify("notifications/initialized", null);

        return new McpServer.ServerInfo(serverName, serverVersion, instructions);
    }

    /**
     * List tools from a connected server.
     */
    private List<McpServer.McpTool> listTools(McpTransportClient transport) throws McpException {
        JsonNode result = transport.request("tools/list", MAPPER.createObjectNode(), DEFAULT_TIMEOUT_MS);

        List<McpServer.McpTool> tools = new ArrayList<>();
        JsonNode toolsArray = result.get("tools");
        if (toolsArray != null && toolsArray.isArray()) {
            for (JsonNode tool : toolsArray) {
                String name = tool.path("name").asText();
                String description = tool.path("description").asText("");
                JsonNode inputSchema = tool.get("inputSchema");
                if (inputSchema == null) {
                    inputSchema = MAPPER.createObjectNode().put("type", "object");
                }
                tools.add(new McpServer.McpTool(name, description, inputSchema));
            }
        }

        return tools;
    }

    private McpServer.Connected findServer(String normalizedName) {
        for (McpServer server : servers.values()) {
            if (server instanceof McpServer.Connected c) {
                if (normalizeName(c.name()).equals(normalizedName)) {
                    return c;
                }
            }
        }
        return null;
    }

    /**
     * Normalize a name for use in tool qualified names.
     * Replaces spaces and special chars with underscores.
     */
    static String normalizeName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
    }
}
