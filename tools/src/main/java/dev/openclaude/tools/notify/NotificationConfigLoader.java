package dev.openclaude.tools.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openclaude.tools.notify.providers.LibnotifyNotificationProvider;
import dev.openclaude.tools.notify.providers.SlackWebhookNotificationProvider;
import dev.openclaude.tools.notify.providers.StdoutNotificationProvider;
import dev.openclaude.tools.notify.providers.WebhookNotificationProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the notification provider list from {@code settings.json#notifications}:
 *
 * <pre>{@code
 * "notifications": {
 *   "providers": ["stdout", "libnotify", "webhook", "slack"],
 *   "webhookUrl": "https://example.com/notify",
 *   "webhookHeaders": {"Authorization": "Bearer ${MY_TOKEN}"},
 *   "slackWebhookUrl": "https://hooks.slack.com/services/..."
 * }
 * }</pre>
 *
 * <p>Header and URL values support {@code ${ENV_VAR}} substitution. Without any
 * config the default is stdout only.
 */
public final class NotificationConfigLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private NotificationConfigLoader() {}

    public static List<NotificationProvider> load() {
        String home = System.getProperty("user.home");
        if (home == null) return List.of(new StdoutNotificationProvider());
        return load(Path.of(home, ".claude", "settings.json"));
    }

    static List<NotificationProvider> load(Path settingsFile) {
        JsonNode notifications = null;
        try {
            if (Files.exists(settingsFile)) {
                notifications = MAPPER.readTree(Files.readString(settingsFile)).get("notifications");
            }
        } catch (Exception ignored) {
            // Unreadable settings — fall back to the default
        }
        if (notifications == null || !notifications.isObject()) {
            return List.of(new StdoutNotificationProvider());
        }

        List<NotificationProvider> providers = new ArrayList<>();
        JsonNode names = notifications.get("providers");
        if (names == null || !names.isArray()) {
            return List.of(new StdoutNotificationProvider());
        }

        for (JsonNode nameNode : names) {
            switch (nameNode.asText()) {
                case "stdout" -> providers.add(new StdoutNotificationProvider());
                case "libnotify" -> providers.add(new LibnotifyNotificationProvider());
                case "webhook" -> {
                    String url = substituteEnvVars(notifications.path("webhookUrl").asText(""));
                    if (!url.isBlank()) {
                        Map<String, String> headers = new HashMap<>();
                        JsonNode headersNode = notifications.get("webhookHeaders");
                        if (headersNode != null && headersNode.isObject()) {
                            headersNode.fieldNames().forEachRemaining(key ->
                                    headers.put(key, substituteEnvVars(headersNode.get(key).asText())));
                        }
                        providers.add(new WebhookNotificationProvider(url, headers));
                    }
                }
                case "slack" -> {
                    String url = substituteEnvVars(notifications.path("slackWebhookUrl").asText(""));
                    if (!url.isBlank()) {
                        providers.add(new SlackWebhookNotificationProvider(url));
                    }
                }
                default -> System.err.println(
                        "[notifications] unknown provider: " + nameNode.asText());
            }
        }

        return providers.isEmpty()
                ? List.of(new StdoutNotificationProvider())
                : List.copyOf(providers);
    }

    static String substituteEnvVars(String input) {
        if (input == null) return null;
        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < input.length()) {
            if (i + 1 < input.length() && input.charAt(i) == '$' && input.charAt(i + 1) == '{') {
                int end = input.indexOf('}', i + 2);
                if (end != -1) {
                    String value = System.getenv(input.substring(i + 2, end));
                    result.append(value != null ? value : "");
                    i = end + 1;
                    continue;
                }
            }
            result.append(input.charAt(i));
            i++;
        }
        return result.toString();
    }
}
