package dev.openclaude.tools.notify;

import com.fasterxml.jackson.databind.JsonNode;
import dev.openclaude.tools.SchemaBuilder;
import dev.openclaude.tools.Tool;
import dev.openclaude.tools.ToolResult;
import dev.openclaude.tools.ToolUseContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Sends a notification to the user through every configured
 * {@link NotificationProvider}. Providers are configured in
 * {@code settings.json#notifications}; the default is stdout only.
 */
public class PushNotificationTool implements Tool {

    private static final Set<String> LEVELS = Set.of("info", "warning", "error");

    private static final JsonNode SCHEMA = SchemaBuilder.object()
            .stringProp("title", "Short notification title.", true)
            .stringProp("body", "Notification body text.", false)
            .enumProp("level", "Severity of the notification. Default: info.",
                    false, "info", "warning", "error")
            .build();

    private final List<NotificationProvider> providers;

    public PushNotificationTool(List<NotificationProvider> providers) {
        this.providers = List.copyOf(providers);
    }

    @Override
    public String name() {
        return "PushNotification";
    }

    @Override
    public String description() {
        return "Sends a notification to the user via the configured channels ("
                + String.join(", ", providers.stream().map(NotificationProvider::name).toList())
                + "). Use for milestones in long-running work, not routine progress.";
    }

    @Override
    public JsonNode inputSchema() {
        return SCHEMA;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(JsonNode input, ToolUseContext context) {
        String title = input.path("title").asText("").strip();
        if (title.isEmpty()) {
            return ToolResult.error("title is required.");
        }
        String body = input.path("body").asText("");
        String level = input.path("level").asText("info");
        if (!LEVELS.contains(level)) {
            level = "info";
        }

        List<String> delivered = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (NotificationProvider provider : providers) {
            try {
                provider.send(title, body, level);
                delivered.add(provider.name());
            } catch (Exception e) {
                failed.add(provider.name() + " (" + e.getMessage() + ")");
            }
        }

        if (delivered.isEmpty()) {
            return ToolResult.error("Notification failed on every channel: "
                    + String.join(", ", failed));
        }
        String result = "Notification sent via: " + String.join(", ", delivered);
        if (!failed.isEmpty()) {
            result += "\nFailed channels: " + String.join(", ", failed);
        }
        return ToolResult.success(result);
    }
}
