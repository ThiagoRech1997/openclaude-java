package dev.openclaude.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.openclaude.mcp.McpException;
import dev.openclaude.mcp.McpTransportClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP transport over HTTP for remote servers.
 *
 * <p>Implements the 2024-11-05 HTTP+SSE flow: a long-lived GET opens an SSE
 * stream, the server announces the POST endpoint via an {@code endpoint} event,
 * requests are POSTed there and responses arrive as {@code message} events.
 *
 * <p>Also interoperates with "streamable HTTP" servers (no SSE channel): if the
 * GET is rejected, requests POST to the base URL and the response is taken
 * directly from the POST body (JSON or a single-response SSE body).
 */
public class HttpSseTransport implements McpTransportClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_RECONNECT_ATTEMPTS = 3;
    private static final long RECONNECT_DELAY_MS = 1_000;
    private static final long ENDPOINT_WAIT_MS = 3_000;

    private final URI baseUri;
    private final Map<String, String> headers;
    private final HttpClient httpClient;
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final ConcurrentHashMap<Integer, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();
    private final AtomicBoolean connected = new AtomicBoolean(true);
    private final CompletableFuture<URI> postEndpoint = new CompletableFuture<>();
    private volatile InputStream sseStream;

    public HttpSseTransport(String url, Map<String, String> headers) throws McpException {
        if (url == null || url.isBlank()) {
            throw new McpException("HTTP/SSE transport requires a url");
        }
        try {
            this.baseUri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new McpException("Invalid MCP server url: " + url, e);
        }
        this.headers = headers != null ? Map.copyOf(headers) : Map.of();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        Thread reader = new Thread(this::connectLoop, "mcp-sse-reader");
        reader.setDaemon(true);
        reader.start();
    }

    @Override
    public JsonNode request(String method, JsonNode params, long timeoutMs) throws McpException {
        if (!connected.get()) {
            throw new McpException("Transport is not connected");
        }

        URI target = resolvePostUri(timeoutMs);
        int id = nextId.getAndIncrement();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingRequests.put(id, future);

        try {
            ObjectNode request = MAPPER.createObjectNode();
            request.put("jsonrpc", "2.0");
            request.put("id", id);
            request.put("method", method);
            if (params != null) {
                request.set("params", params);
            }

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(target)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .timeout(Duration.ofMillis(timeoutMs))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            MAPPER.writeValueAsString(request), StandardCharsets.UTF_8));
            headers.forEach(builder::header);

            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() >= 400) {
                pendingRequests.remove(id);
                throw new McpException("MCP server returned HTTP " + response.statusCode()
                        + " for " + method);
            }

            // Streamable-HTTP servers answer in the POST body itself; legacy
            // SSE servers answer 202 with an empty body and reply on the stream
            String body = response.body();
            if (body != null && !body.isBlank()) {
                String contentType = response.headers().firstValue("Content-Type").orElse("");
                if (contentType.contains("text/event-stream")) {
                    dispatchSseBody(body);
                } else {
                    dispatch(MAPPER.readTree(body));
                }
            }

            return future.get(timeoutMs, TimeUnit.MILLISECONDS);

        } catch (TimeoutException e) {
            pendingRequests.remove(id);
            throw new McpException("Request timed out after " + timeoutMs + "ms: " + method);
        } catch (ExecutionException e) {
            throw new McpException("Request failed: " + e.getCause().getMessage(), e.getCause());
        } catch (McpException e) {
            throw e;
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

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(resolvePostUri(ENDPOINT_WAIT_MS))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            MAPPER.writeValueAsString(notification), StandardCharsets.UTF_8));
            headers.forEach(builder::header);

            httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            throw new McpException("Failed to send notification: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isConnected() {
        return connected.get();
    }

    @Override
    public void close() {
        connected.set(false);
        InputStream stream = sseStream;
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
        }
        failPending("Connection closed");
    }

    // --- Internal ---

    /**
     * The POST target: the endpoint announced on the SSE stream, or the base
     * URL when the server has no SSE channel (streamable HTTP).
     */
    private URI resolvePostUri(long timeoutMs) {
        try {
            return postEndpoint.get(Math.min(ENDPOINT_WAIT_MS, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return baseUri;
        }
    }

    /**
     * Open the SSE channel and keep reading events; reconnect on stream loss.
     * A server that rejects the GET simply has no SSE channel — that is not an
     * error, requests then flow through direct POST responses.
     */
    private void connectLoop() {
        int attempts = 0;
        while (connected.get() && attempts <= MAX_RECONNECT_ATTEMPTS) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(baseUri)
                        .header("Accept", "text/event-stream")
                        .GET();
                headers.forEach(builder::header);

                HttpResponse<InputStream> response = httpClient.send(builder.build(),
                        HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() != 200) {
                    try (InputStream ignored = response.body()) {
                        return; // no SSE channel; direct-POST mode
                    }
                }

                attempts = 0;
                sseStream = response.body();
                readSse(sseStream); // returns on EOF
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ignored) {
                // Connection failure — retry below
            }

            if (!connected.get()) break;
            attempts++;
            failPending("SSE stream lost");
            try {
                Thread.sleep(RECONNECT_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void readSse(InputStream stream) throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8));
        String eventName = null;
        StringBuilder data = new StringBuilder();

        String line;
        while (connected.get() && (line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                handleEvent(eventName, data.toString());
                eventName = null;
                data.setLength(0);
                continue;
            }
            if (line.startsWith(":")) continue; // SSE comment
            if (line.startsWith("event:")) {
                eventName = line.substring(6).trim();
            } else if (line.startsWith("data:")) {
                if (data.length() > 0) data.append('\n');
                data.append(line.substring(5).trim());
            }
        }
    }

    private void handleEvent(String eventName, String data) {
        if (data.isBlank()) return;

        if ("endpoint".equals(eventName)) {
            try {
                postEndpoint.complete(baseUri.resolve(data));
            } catch (IllegalArgumentException ignored) {
                // Malformed endpoint — keep posting to the base URL
            }
            return;
        }

        // "message" events (the default event type) carry JSON-RPC payloads
        try {
            dispatch(MAPPER.readTree(data));
        } catch (Exception ignored) {
            // Skip malformed messages
        }
    }

    /** Parse a complete SSE body returned inline on a POST (streamable HTTP). */
    private void dispatchSseBody(String body) {
        String eventName = null;
        StringBuilder data = new StringBuilder();
        for (String line : body.split("\n", -1)) {
            line = line.stripTrailing();
            if (line.isEmpty()) {
                handleEvent(eventName, data.toString());
                eventName = null;
                data.setLength(0);
                continue;
            }
            if (line.startsWith(":")) continue;
            if (line.startsWith("event:")) {
                eventName = line.substring(6).trim();
            } else if (line.startsWith("data:")) {
                if (data.length() > 0) data.append('\n');
                data.append(line.substring(5).trim());
            }
        }
        handleEvent(eventName, data.toString());
    }

    private void dispatch(JsonNode message) {
        if (message == null || !message.has("id") || message.get("id").isNull()) {
            return; // server notifications are ignored for now
        }
        int id = message.get("id").asInt();
        CompletableFuture<JsonNode> future = pendingRequests.remove(id);
        if (future == null) return;

        if (message.has("error")) {
            JsonNode error = message.get("error");
            future.completeExceptionally(new McpException(
                    error.path("message").asText("Unknown error"),
                    error.path("code").asInt(-1)));
        } else {
            future.complete(message.get("result"));
        }
    }

    private void failPending(String reason) {
        for (var entry : pendingRequests.entrySet()) {
            entry.getValue().completeExceptionally(new McpException(reason));
        }
        pendingRequests.clear();
    }
}
