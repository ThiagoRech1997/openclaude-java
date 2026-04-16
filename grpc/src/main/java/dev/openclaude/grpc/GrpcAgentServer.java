package dev.openclaude.grpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.openclaude.core.model.StreamEvent;
import dev.openclaude.engine.BackgroundAgentManager;
import dev.openclaude.engine.EngineEvent;
import dev.openclaude.engine.QueryEngine;
import dev.openclaude.llm.LlmClient;
import dev.openclaude.tools.ToolRegistry;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Headless JSON-over-TCP server for embedding openclaude-java in other applications.
 *
 * This is a lightweight alternative to full gRPC. When the protobuf/gRPC Gradle plugin
 * is configured, this can be replaced with a proper gRPC implementation using the
 * openclaude.proto definition.
 *
 * Protocol: newline-delimited JSON over TCP.
 *
 * Client sends:
 *   {"type": "chat", "message": "...", "working_directory": "..."}
 *   {"type": "cancel"}
 *
 * Server responds with events:
 *   {"type": "text", "text": "..."}
 *   {"type": "tool_start", "tool_name": "...", "tool_use_id": "..."}
 *   {"type": "tool_result", "tool_name": "...", "output": "...", "is_error": false}
 *   {"type": "done", "full_text": "...", "input_tokens": N, "output_tokens": N}
 *   {"type": "error", "message": "..."}
 */
public class GrpcAgentServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LlmClient client;
    private final ToolRegistry toolRegistry;
    private final String model;
    private final String systemPrompt;
    private final int maxTokens;
    private final int port;
    private final BackgroundAgentManager backgroundManager;
    private volatile boolean running = false;
    private ServerSocket serverSocket;

    public GrpcAgentServer(LlmClient client, ToolRegistry toolRegistry,
                           String model, String systemPrompt, int maxTokens, int port,
                           BackgroundAgentManager backgroundManager) {
        this.client = client;
        this.toolRegistry = toolRegistry;
        this.model = model;
        this.systemPrompt = systemPrompt;
        this.maxTokens = maxTokens;
        this.port = port;
        this.backgroundManager = backgroundManager;
    }

    /**
     * Start the server. Blocks until stopped.
     */
    public void start() throws IOException {
        running = true;
        serverSocket = new ServerSocket(port);
        ExecutorService executor = Executors.newCachedThreadPool();

        System.out.println("openclaude-java server listening on port " + port);

        while (running) {
            try {
                Socket socket = serverSocket.accept();
                executor.submit(() -> handleClient(socket));
            } catch (IOException e) {
                if (running) {
                    System.err.println("Accept error: " + e.getMessage());
                }
            }
        }

        executor.shutdown();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {}
    }

    private void handleClient(Socket socket) {
        try (socket;
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter writer = new PrintWriter(
                     new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {

            String line;
            while ((line = reader.readLine()) != null) {
                var request = MAPPER.readTree(line);
                String type = request.path("type").asText("");

                if ("chat".equals(type)) {
                    String message = request.path("message").asText("");
                    String workDir = request.path("working_directory").asText(
                            System.getProperty("user.dir"));

                    handleChat(message, Path.of(workDir), writer);
                } else if ("cancel".equals(type)) {
                    // Cancel support would require AbortController-like mechanism
                    sendEvent(writer, "error", "Cancel not yet implemented");
                }
            }
        } catch (Exception e) {
            // Client disconnected
        }
    }

    private void handleChat(String message, Path workDir, PrintWriter writer) {
        QueryEngine engine = new QueryEngine(
                client, toolRegistry, model, systemPrompt,
                maxTokens, workDir, event -> {
            try {
                if (event instanceof EngineEvent.Stream s) {
                    if (s.event() instanceof StreamEvent.TextDelta td) {
                        ObjectNode obj = MAPPER.createObjectNode();
                        obj.put("type", "text");
                        obj.put("text", td.text());
                        writer.println(MAPPER.writeValueAsString(obj));
                    }
                } else if (event instanceof EngineEvent.ToolExecutionStart tes) {
                    ObjectNode obj = MAPPER.createObjectNode();
                    obj.put("type", "tool_start");
                    obj.put("tool_name", tes.toolName());
                    obj.put("tool_use_id", tes.toolUseId());
                    writer.println(MAPPER.writeValueAsString(obj));
                } else if (event instanceof EngineEvent.ToolExecutionEnd tee) {
                    ObjectNode obj = MAPPER.createObjectNode();
                    obj.put("type", "tool_result");
                    obj.put("tool_name", tee.toolName());
                    obj.put("output", tee.result().content());
                    obj.put("is_error", tee.result().isError());
                    obj.put("tool_use_id", tee.toolUseId());
                    writer.println(MAPPER.writeValueAsString(obj));
                } else if (event instanceof EngineEvent.Done done) {
                    ObjectNode obj = MAPPER.createObjectNode();
                    obj.put("type", "done");
                    obj.put("input_tokens", done.totalUsage().inputTokens());
                    obj.put("output_tokens", done.totalUsage().outputTokens());
                    writer.println(MAPPER.writeValueAsString(obj));
                } else if (event instanceof EngineEvent.Error err) {
                    sendEvent(writer, "error", err.message());
                }
            } catch (Exception e) {
                // Ignore serialization errors
            }
        }, backgroundManager);

        engine.run(message);
    }

    private void sendEvent(PrintWriter writer, String type, String message) {
        try {
            ObjectNode obj = MAPPER.createObjectNode();
            obj.put("type", type);
            obj.put("message", message);
            writer.println(MAPPER.writeValueAsString(obj));
        } catch (Exception ignored) {}
    }
}
