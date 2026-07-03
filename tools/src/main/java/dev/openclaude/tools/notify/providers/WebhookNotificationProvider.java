package dev.openclaude.tools.notify.providers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.openclaude.tools.notify.NotificationProvider;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Generic webhook: POSTs {@code {"title", "body", "level"}} as JSON to the
 * configured URL with optional extra headers.
 */
public class WebhookNotificationProvider implements NotificationProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String url;
    private final Map<String, String> headers;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public WebhookNotificationProvider(String url, Map<String, String> headers) {
        this.url = url;
        this.headers = headers != null ? Map.copyOf(headers) : Map.of();
    }

    @Override
    public String name() {
        return "webhook";
    }

    @Override
    public void send(String title, String body, String level) throws Exception {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("title", title);
        payload.put("body", body);
        payload.put("level", level);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(
                        MAPPER.writeValueAsString(payload), StandardCharsets.UTF_8));
        headers.forEach(builder::header);

        HttpResponse<Void> response = httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("Webhook returned HTTP " + response.statusCode());
        }
    }
}
