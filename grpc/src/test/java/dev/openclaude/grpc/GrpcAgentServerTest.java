package dev.openclaude.grpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openclaude.core.model.StreamEvent;
import dev.openclaude.core.model.Usage;
import dev.openclaude.llm.LlmClient;
import dev.openclaude.llm.LlmRequest;
import dev.openclaude.tools.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class GrpcAgentServerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final LlmClient ECHO_CLIENT = new LlmClient() {
        @Override
        public void streamMessage(LlmRequest request, Consumer<StreamEvent> handler) {
            handler.accept(new StreamEvent.MessageStart("m", "test", Usage.ZERO));
            handler.accept(new StreamEvent.TextDelta("pong"));
            handler.accept(new StreamEvent.ContentBlockStop(0));
            handler.accept(new StreamEvent.MessageDelta("end_turn", new Usage(1, 1, 0, 0)));
        }

        @Override
        public String providerName() {
            return "test";
        }
    };

    private GrpcAgentServer server;
    private Thread serverThread;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
        if (serverThread != null) serverThread.interrupt();
    }

    @Test
    @Timeout(15)
    void chatOnLoopback_withoutToken_works() throws Exception {
        startServer(null);

        try (Socket socket = new Socket("127.0.0.1", server.localPort())) {
            List<JsonNode> frames = roundTrip(socket,
                    "{\"type\":\"chat\",\"message\":\"ping\"}");

            assertTrue(frames.stream().anyMatch(f ->
                            "text".equals(f.path("type").asText())
                                    && "pong".equals(f.path("text").asText())),
                    "expected a text frame: " + frames);
            assertTrue(frames.stream().anyMatch(f -> "done".equals(f.path("type").asText())),
                    "expected a done frame: " + frames);
        }
    }

    @Test
    @Timeout(15)
    void tokenConfigured_unauthenticatedChatIsRejected() throws Exception {
        startServer("s3cret");

        try (Socket socket = new Socket("127.0.0.1", server.localPort())) {
            List<JsonNode> frames = roundTrip(socket,
                    "{\"type\":\"chat\",\"message\":\"ping\"}");

            assertEquals(1, frames.size());
            assertEquals("error", frames.get(0).path("type").asText());
            assertTrue(frames.get(0).path("message").asText().contains("Authentication required"));
        }
    }

    @Test
    @Timeout(15)
    void tokenConfigured_authThenChat_works() throws Exception {
        startServer("s3cret");

        try (Socket socket = new Socket("127.0.0.1", server.localPort())) {
            PrintWriter writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            writer.println("{\"type\":\"auth\",\"token\":\"s3cret\"}");
            JsonNode authReply = MAPPER.readTree(reader.readLine());
            assertEquals("auth_ok", authReply.path("type").asText());

            writer.println("{\"type\":\"chat\",\"message\":\"ping\"}");
            List<JsonNode> frames = readUntilDone(reader);
            assertTrue(frames.stream().anyMatch(f -> "done".equals(f.path("type").asText())));
        }
    }

    @Test
    void nonLoopbackBind_withoutToken_refusesToStart() {
        GrpcAgentServer exposed = new GrpcAgentServer(
                ECHO_CLIENT, new ToolRegistry(), "m", "s", 128, 0,
                null, null, "0.0.0.0", null);

        IOException e = assertThrows(IOException.class, exposed::start);
        assertTrue(e.getMessage().contains("OPENCLAUDE_SERVE_TOKEN"));
    }

    // ---------- helpers ----------

    private void startServer(String token) throws Exception {
        server = new GrpcAgentServer(
                ECHO_CLIENT, new ToolRegistry(), "test-model", "sys", 128, 0,
                null, null, "127.0.0.1", token);
        serverThread = new Thread(() -> {
            try {
                server.start();
            } catch (IOException ignored) {
            }
        }, "test-server");
        serverThread.setDaemon(true);
        serverThread.start();

        long deadline = System.currentTimeMillis() + 5_000;
        while (server.localPort() <= 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(server.localPort() > 0, "server did not start in time");
    }

    private static List<JsonNode> roundTrip(Socket socket, String requestLine) throws IOException {
        PrintWriter writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        writer.println(requestLine);
        socket.shutdownOutput();
        return readUntilDone(reader);
    }

    /** Read frames until done/error or EOF. */
    private static List<JsonNode> readUntilDone(BufferedReader reader) throws IOException {
        List<JsonNode> frames = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
            JsonNode frame = MAPPER.readTree(line);
            frames.add(frame);
            String type = frame.path("type").asText();
            if ("done".equals(type) || "error".equals(type)) break;
        }
        return frames;
    }
}
