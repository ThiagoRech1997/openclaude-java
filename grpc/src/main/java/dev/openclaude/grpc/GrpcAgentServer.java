package dev.openclaude.grpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.openclaude.core.model.StreamEvent;
import dev.openclaude.core.permissions.PermissionManager;
import dev.openclaude.engine.BackgroundAgentManager;
import dev.openclaude.engine.EngineEvent;
import dev.openclaude.engine.QueryEngine;
import dev.openclaude.llm.LlmClient;
import dev.openclaude.tools.ToolRegistry;

import java.io.*;
import java.net.InetAddress;
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
 *
 * Security: binds the loopback interface by default. Set OPENCLAUDE_SERVE_HOST
 * to expose another interface — this then REQUIRES OPENCLAUDE_SERVE_TOKEN, and
 * clients must authenticate first: {"type": "auth", "token": "..."}.
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
    private final PermissionManager permissions;
    private final String bindHost;
    private final String authToken;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private volatile boolean running = false;
    private volatile ServerSocket serverSocket;

    /** Cap on a single request line so one client cannot exhaust the heap. */
    private static final int MAX_LINE_CHARS = 10_000_000;

    public GrpcAgentServer(LlmClient client, ToolRegistry toolRegistry,
                           String model, String systemPrompt, int maxTokens, int port,
                           BackgroundAgentManager backgroundManager) {
        this(client, toolRegistry, model, systemPrompt, maxTokens, port, backgroundManager, null);
    }

    public GrpcAgentServer(LlmClient client, ToolRegistry toolRegistry,
                           String model, String systemPrompt, int maxTokens, int port,
                           BackgroundAgentManager backgroundManager,
                           PermissionManager permissions) {
        this(client, toolRegistry, model, systemPrompt, maxTokens, port, backgroundManager, permissions,
                System.getenv().getOrDefault("OPENCLAUDE_SERVE_HOST", "127.0.0.1"),
                System.getenv("OPENCLAUDE_SERVE_TOKEN"));
    }

    GrpcAgentServer(LlmClient client, ToolRegistry toolRegistry,
                    String model, String systemPrompt, int maxTokens, int port,
                    BackgroundAgentManager backgroundManager,
                    PermissionManager permissions,
                    String bindHost, String authToken) {
        this.client = client;
        this.toolRegistry = toolRegistry;
        this.model = model;
        this.systemPrompt = systemPrompt;
        this.maxTokens = maxTokens;
        this.port = port;
        this.backgroundManager = backgroundManager;
        this.permissions = permissions;
        this.bindHost = bindHost;
        this.authToken = authToken;
    }

    /**
     * Start the server. Blocks until stopped.
     */
    public void start() throws IOException {
        InetAddress bindAddress = InetAddress.getByName(bindHost);
        if (!bindAddress.isLoopbackAddress() && (authToken == null || authToken.isBlank())) {
            throw new IOException("Refusing to bind non-loopback address " + bindHost
                    + " without OPENCLAUDE_SERVE_TOKEN set");
        }

        running = true;
        serverSocket = new ServerSocket(port, 50, bindAddress);

        System.out.println("openclaude-java server listening on " + bindHost + ":" + localPort());

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

    /** Actual bound port (differs from the configured one when it is 0). */
    public int localPort() {
        ServerSocket socket = serverSocket;
        return socket != null ? socket.getLocalPort() : -1;
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {}
        executor.shutdownNow();
    }

    private void handleClient(Socket socket) {
        try (socket;
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter writer = new PrintWriter(
                     new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {

            boolean authenticated = authToken == null || authToken.isBlank();

            String line;
            while ((line = readBoundedLine(reader)) != null) {
                var request = MAPPER.readTree(line);
                String type = request.path("type").asText("");

                if (!authenticated) {
                    if ("auth".equals(type)
                            && authToken.equals(request.path("token").asText(""))) {
                        authenticated = true;
                        sendEvent(writer, "auth_ok", "authenticated");
                        continue;
                    }
                    sendEvent(writer, "error", "Authentication required");
                    return; // one bad frame closes the connection
                }

                if ("chat".equals(type)) {
                    String message = request.path("message").asText("");
                    String workDir = request.path("working_directory").asText(
                            System.getProperty("user.dir"));

                    try {
                        handleChat(message, Path.of(workDir), writer);
                    } catch (Exception e) {
                        // The client must always get a terminal frame, not a hang
                        sendEvent(writer, "error", "Chat failed: " + e.getMessage());
                    }
                } else if ("cancel".equals(type)) {
                    // Cancel support would require AbortController-like mechanism
                    sendEvent(writer, "error", "Cancel not yet implemented");
                } else {
                    sendEvent(writer, "error", "Unknown request type: " + type);
                }
            }
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            // Malformed JSON: nothing sane to answer on a line protocol — drop the client
        } catch (Exception e) {
            // Client disconnected
        }
    }

    /**
     * readLine with a size cap — an unbounded line from a client would
     * otherwise grow the buffer until the heap is exhausted.
     */
    private static String readBoundedLine(BufferedReader reader) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = reader.read()) != -1) {
            if (c == '\n') return sb.toString();
            if (c == '\r') continue;
            if (sb.length() >= MAX_LINE_CHARS) {
                throw new IOException("Request line exceeds " + MAX_LINE_CHARS + " chars");
            }
            sb.append((char) c);
        }
        return sb.length() > 0 ? sb.toString() : null;
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
                    obj.put("output", tee.result().textContent());
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
        }, backgroundManager, null, permissions, null);

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
