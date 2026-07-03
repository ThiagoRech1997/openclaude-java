package dev.openclaude.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class McpClientManagerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void callTool_sendsOriginalToolName_notNormalized() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.results.add(MAPPER.readTree(
                "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}"));

        McpClientManager manager = new McpClientManager();
        manager.register(new McpServer.Connected(
                "My Server", transport,
                new McpServer.ServerInfo("s", "1", ""),
                List.of(new McpServer.McpTool("notion-search", "d", MAPPER.createObjectNode()))));

        List<McpServer.McpTool> tools = manager.allTools();
        assertEquals("mcp__my_server__notion_search", tools.get(0).name());

        String result = manager.callTool(tools.get(0).name(), MAPPER.createObjectNode());

        assertEquals("ok", result);
        assertEquals("tools/call", transport.lastMethod);
        assertEquals("notion-search", transport.lastParams.path("name").asText(),
                "server must receive its own tool name, not the normalized one");
    }

    @Test
    void allTools_disambiguatesNormalizationCollisions() {
        FakeTransport transport = new FakeTransport();
        McpClientManager manager = new McpClientManager();
        manager.register(new McpServer.Connected(
                "srv", transport,
                new McpServer.ServerInfo("s", "1", ""),
                List.of(new McpServer.McpTool("foo-bar", "d", MAPPER.createObjectNode()),
                        new McpServer.McpTool("foo_bar", "d", MAPPER.createObjectNode()))));

        List<McpServer.McpTool> tools = manager.allTools();

        assertEquals(2, tools.size());
        assertEquals("mcp__srv__foo_bar", tools.get(0).name());
        assertEquals("mcp__srv__foo_bar_2", tools.get(1).name());

        // Repeated listing must be stable
        List<McpServer.McpTool> again = manager.allTools();
        assertEquals(tools.get(0).name(), again.get(0).name());
        assertEquals(tools.get(1).name(), again.get(1).name());
    }

    @Test
    void listTools_followsPagination() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.results.add(MAPPER.readTree(
                "{\"tools\":[{\"name\":\"a\",\"description\":\"\"}],\"nextCursor\":\"c1\"}"));
        transport.results.add(MAPPER.readTree(
                "{\"tools\":[{\"name\":\"b\",\"description\":\"\"}]}"));

        List<McpServer.McpTool> tools = new McpClientManager().listTools(transport);

        assertEquals(2, tools.size());
        assertEquals("a", tools.get(0).name());
        assertEquals("b", tools.get(1).name());
        assertEquals(2, transport.paramsHistory.size());
        assertFalse(transport.paramsHistory.get(0).has("cursor"));
        assertEquals("c1", transport.paramsHistory.get(1).path("cursor").asText());
    }

    private static final class FakeTransport implements McpTransportClient {
        final Deque<JsonNode> results = new ArrayDeque<>();
        final List<JsonNode> paramsHistory = new ArrayList<>();
        String lastMethod;
        JsonNode lastParams;

        @Override
        public JsonNode request(String method, JsonNode params, long timeoutMs) {
            lastMethod = method;
            lastParams = params;
            paramsHistory.add(params);
            return results.isEmpty() ? MAPPER.createObjectNode() : results.poll();
        }

        @Override
        public void notify(String method, JsonNode params) {
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void close() {
        }
    }
}
