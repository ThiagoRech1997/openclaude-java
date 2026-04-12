package dev.openclaude.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import dev.openclaude.tools.Tool;
import dev.openclaude.tools.ToolResult;
import dev.openclaude.tools.ToolUseContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridges MCP tools into the agent's Tool interface.
 * Each MCP tool becomes a Tool that delegates execution to the MCP server.
 */
public final class McpToolBridge {

    private McpToolBridge() {}

    /**
     * Create Tool objects for all tools from all connected MCP servers.
     */
    public static List<Tool> createTools(McpClientManager manager) {
        List<Tool> tools = new ArrayList<>();

        for (McpServer.McpTool mcpTool : manager.allTools()) {
            tools.add(new McpToolAdapter(mcpTool, manager));
        }

        return tools;
    }

    /**
     * Adapter that wraps an MCP tool as an agent Tool.
     */
    private static class McpToolAdapter implements Tool {

        private final McpServer.McpTool mcpTool;
        private final McpClientManager manager;

        McpToolAdapter(McpServer.McpTool mcpTool, McpClientManager manager) {
            this.mcpTool = mcpTool;
            this.manager = manager;
        }

        @Override
        public String name() {
            return mcpTool.name();
        }

        @Override
        public String description() {
            return mcpTool.description();
        }

        @Override
        public JsonNode inputSchema() {
            return mcpTool.inputSchema();
        }

        @Override
        public boolean isReadOnly() {
            return false; // MCP tools could be anything
        }

        @Override
        public ToolResult execute(JsonNode input, ToolUseContext context) {
            try {
                String result = manager.callTool(mcpTool.name(), input);
                return ToolResult.success(result.isEmpty() ? "(no output)" : result);
            } catch (McpException e) {
                return ToolResult.error("MCP tool error: " + e.getMessage());
            }
        }
    }
}
