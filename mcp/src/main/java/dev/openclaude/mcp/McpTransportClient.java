package dev.openclaude.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Transport abstraction for MCP JSON-RPC communication.
 * Implementations handle the physical connection (stdio, SSE, HTTP, WebSocket).
 */
public interface McpTransportClient extends AutoCloseable {

    /**
     * Send a JSON-RPC request and wait for the response.
     *
     * @param method the RPC method (e.g., "tools/list", "tools/call")
     * @param params the request parameters
     * @param timeoutMs timeout in milliseconds
     * @return the result field from the JSON-RPC response
     * @throws McpException if the server returns an error or communication fails
     */
    JsonNode request(String method, JsonNode params, long timeoutMs) throws McpException;

    /**
     * Send a JSON-RPC notification (no response expected).
     */
    void notify(String method, JsonNode params) throws McpException;

    /**
     * Whether the transport is currently connected.
     */
    boolean isConnected();

    @Override
    void close();
}
