package dev.openclaude.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.openclaude.mcp.McpException;
import dev.openclaude.mcp.McpTransportClient;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MCP transport over stdio (subprocess).
 * Spawns the server process, sends JSON-RPC messages via stdin,
 * reads responses from stdout. Each line is a complete JSON-RPC message.
 */
public class StdioTransport implements McpTransportClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Process process;
    private final BufferedWriter writer;
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final ConcurrentHashMap<Integer, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();
    private final AtomicBoolean connected = new AtomicBoolean(true);
    private final Thread readerThread;

    /**
     * Create a stdio transport by spawning a subprocess.
     *
     * @param command the command to run (e.g., "npx")
     * @param args command arguments (e.g., ["-y", "@modelcontextprotocol/server-filesystem", "/path"])
     * @param env additional environment variables
     * @param workingDir working directory for the process
     */
    public StdioTransport(String command, List<String> args, Map<String, String> env, File workingDir)
            throws McpException {
        try {
            List<String> cmd = new java.util.ArrayList<>();
            cmd.add(command);
            cmd.addAll(args);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (workingDir != null) {
                pb.directory(workingDir);
            }
            pb.environment().putAll(env != null ? env : Map.of());
            pb.redirectErrorStream(false); // Keep stderr separate

            this.process = pb.start();
            this.writer = new BufferedWriter(
                    new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

            // Start reader thread to process stdout responses
            this.readerThread = new Thread(this::readLoop, "mcp-stdio-reader");
            readerThread.setDaemon(true);
            readerThread.start();

            // Drain stderr in background to prevent blocking
            Thread stderrDrain = new Thread(() -> drainStream(process.getErrorStream()), "mcp-stderr-drain");
            stderrDrain.setDaemon(true);
            stderrDrain.start();

        } catch (IOException e) {
            throw new McpException("Failed to start MCP server process: " + e.getMessage(), e);
        }
    }

    @Override
    public JsonNode request(String method, JsonNode params, long timeoutMs) throws McpException {
        if (!connected.get()) {
            throw new McpException("Transport is not connected");
        }

        int id = nextId.getAndIncrement();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingRequests.put(id, future);

        try {
            // Build JSON-RPC request
            ObjectNode request = MAPPER.createObjectNode();
            request.put("jsonrpc", "2.0");
            request.put("id", id);
            request.put("method", method);
            if (params != null) {
                request.set("params", params);
            }

            // Send via stdin
            synchronized (writer) {
                writer.write(MAPPER.writeValueAsString(request));
                writer.newLine();
                writer.flush();
            }

            // Wait for response
            JsonNode result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            return result;

        } catch (TimeoutException e) {
            pendingRequests.remove(id);
            throw new McpException("Request timed out after " + timeoutMs + "ms: " + method);
        } catch (ExecutionException e) {
            throw new McpException("Request failed: " + e.getCause().getMessage(), e.getCause());
        } catch (Exception e) {
            pendingRequests.remove(id);
            throw new McpException("Failed to send request: " + e.getMessage(), e);
        }
    }

    @Override
    public void notify(String method, JsonNode params) throws McpException {
        if (!connected.get()) return;

        try {
            ObjectNode notification = MAPPER.createObjectNode();
            notification.put("jsonrpc", "2.0");
            notification.put("method", method);
            if (params != null) {
                notification.set("params", params);
            }

            synchronized (writer) {
                writer.write(MAPPER.writeValueAsString(notification));
                writer.newLine();
                writer.flush();
            }
        } catch (IOException e) {
            throw new McpException("Failed to send notification: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isConnected() {
        return connected.get() && process.isAlive();
    }

    @Override
    public void close() {
        connected.set(false);

        // Complete all pending requests with error
        for (var entry : pendingRequests.entrySet()) {
            entry.getValue().completeExceptionally(
                    new McpException("Connection closed"));
        }
        pendingRequests.clear();

        // Close streams and kill process
        try {
            writer.close();
        } catch (IOException ignored) {}

        process.destroyForcibly();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Read JSON-RPC messages from stdout in a loop.
     */
    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (connected.get() && (line = reader.readLine()) != null) {
                if (line.isBlank()) continue;

                try {
                    JsonNode message = MAPPER.readTree(line);

                    // Check if this is a response (has "id" field)
                    if (message.has("id") && !message.get("id").isNull()) {
                        int id = message.get("id").asInt();
                        CompletableFuture<JsonNode> future = pendingRequests.remove(id);
                        if (future != null) {
                            if (message.has("error")) {
                                JsonNode error = message.get("error");
                                future.completeExceptionally(new McpException(
                                        error.path("message").asText("Unknown error"),
                                        error.path("code").asInt(-1)));
                            } else {
                                future.complete(message.get("result"));
                            }
                        }
                    }
                    // Notifications from server (no id) are ignored for now
                } catch (Exception e) {
                    // Skip malformed messages
                }
            }
        } catch (IOException e) {
            if (connected.get()) {
                connected.set(false);
            }
        }
    }

    private void drainStream(InputStream stream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            while (reader.readLine() != null) {
                // discard stderr
            }
        } catch (IOException ignored) {}
    }
}
