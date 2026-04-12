package dev.openclaude.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Represents an MCP server connection and its state.
 */
public sealed interface McpServer {

    String name();

    record Connected(
            String name,
            McpTransportClient transport,
            ServerInfo serverInfo,
            List<McpTool> tools
    ) implements McpServer {}

    record Failed(
            String name,
            String error
    ) implements McpServer {}

    record Pending(
            String name
    ) implements McpServer {}

    /**
     * Info about the MCP server reported during initialization.
     */
    record ServerInfo(
            String name,
            String version,
            String instructions
    ) {}

    /**
     * A tool exposed by an MCP server.
     */
    record McpTool(
            String name,
            String description,
            JsonNode inputSchema
    ) {}
}
