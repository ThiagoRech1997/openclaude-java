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

/**
 * Slack incoming webhook: posts {@code {"text": ...}} in Slack markdown.
 */
public class SlackWebhookNotificationProvider implements NotificationProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String webhookUrl;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public SlackWebhookNotificationProvider(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    @Override
    public String name() {
        return "slack";
    }

    @Override
    public void send(String title, String body, String level) throws Exception {
        String prefix = switch (level) {
            case "error" -> ":red_circle: ";
            case "warning" -> ":warning: ";
            default -> "";
        };
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("text", prefix + "*" + title + "*"
                + (body.isBlank() ? "" : "\n" + body));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(
                        MAPPER.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();

        HttpResponse<Void> response = httpClient.send(request,
                HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("Slack webhook returned HTTP " + response.statusCode());
        }
    }
}
