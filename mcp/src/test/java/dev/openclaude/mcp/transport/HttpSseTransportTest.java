package dev.openclaude.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.openclaude.mcp.McpException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class HttpSseTransportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private HttpSseTransport transport;

    @AfterEach
    void tearDown() {
        if (transport != null) transport.close();
        if (server != null) server.stop(0);
    }

    @Test
    @Timeout(20)
    void legacySse_endpointEventAndStreamedResponse() throws Exception {
        AtomicReference<OutputStream> sseOut = new AtomicReference<>();
        CountDownLatch keepSseOpen = new CountDownLatch(1);
        List<String> authHeaders = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());

        server.createContext("/mcp", exchange -> {
            authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream os = exchange.getResponseBody();
            os.write("event: endpoint\ndata: /messages\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
            sseOut.set(os);
            try {
                // The handler must not return or the server closes the SSE stream
                keepSseOpen.await(15, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            exchange.close();
        });

        server.createContext("/messages", exchange -> {
            authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            JsonNode request = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
            exchange.sendResponseHeaders(202, -1); // accepted; reply goes via SSE
            String reply = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":"
                    + request.get("id").asInt() + ",\"result\":{\"ok\":true}}\n\n";
            OutputStream os = sseOut.get();
            os.write(reply.getBytes(StandardCharsets.UTF_8));
            os.flush();
        });
        server.start();

        transport = new HttpSseTransport(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp",
                Map.of("Authorization", "Bearer test-token"));

        JsonNode result = transport.request("test/method", MAPPER.createObjectNode(), 10_000);

        assertTrue(result.path("ok").asBoolean());
        assertTrue(authHeaders.stream().allMatch("Bearer test-token"::equals),
                "auth header must go on both the SSE GET and the POST: " + authHeaders);
        keepSseOpen.countDown();
    }

    @Test
    @Timeout(20)
    void streamableHttp_directJsonResponse_worksWithoutSseChannel() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());

        server.createContext("/direct", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1); // no SSE channel
                return;
            }
            JsonNode request = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
            byte[] reply = ("{\"jsonrpc\":\"2.0\",\"id\":" + request.get("id").asInt()
                    + ",\"result\":{\"mode\":\"direct\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, reply.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(reply);
            }
        });
        server.start();

        transport = new HttpSseTransport(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/direct", Map.of());

        JsonNode result = transport.request("test/method", MAPPER.createObjectNode(), 10_000);

        assertEquals("direct", result.path("mode").asText());
    }

    @Test
    @Timeout(20)
    void serverError_throwsMcpException() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/broken", exchange -> exchange.sendResponseHeaders(500, -1));
        server.start();

        transport = new HttpSseTransport(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/broken", Map.of());

        McpException e = assertThrows(McpException.class,
                () -> transport.request("test/method", MAPPER.createObjectNode(), 10_000));
        assertTrue(e.getMessage().contains("500"));
    }
}
