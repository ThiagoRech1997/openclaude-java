package dev.openclaude.tools.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import dev.openclaude.tools.ToolResult;
import dev.openclaude.tools.ToolUseContext;
import dev.openclaude.tools.notify.providers.WebhookNotificationProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PushNotificationToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolUseContext context = new ToolUseContext(Path.of("."));

    private record Sent(String title, String body, String level) {}

    private static final class RecordingProvider implements NotificationProvider {
        final List<Sent> sent = new ArrayList<>();
        private final String name;
        private final boolean fail;

        RecordingProvider(String name, boolean fail) {
            this.name = name;
            this.fail = fail;
        }

        @Override public String name() { return name; }

        @Override
        public void send(String title, String body, String level) {
            if (fail) throw new IllegalStateException("boom");
            sent.add(new Sent(title, body, level));
        }
    }

    private static ObjectNode input(String title, String body) {
        return MAPPER.createObjectNode().put("title", title).put("body", body);
    }

    @Test
    void sendsThroughAllProviders() {
        RecordingProvider a = new RecordingProvider("a", false);
        RecordingProvider b = new RecordingProvider("b", false);
        PushNotificationTool tool = new PushNotificationTool(List.of(a, b));

        ToolResult result = tool.execute(input("done", "build finished"), context);

        assertFalse(result.isError());
        assertEquals(1, a.sent.size());
        assertEquals(1, b.sent.size());
        assertEquals("info", a.sent.get(0).level(), "level defaults to info");
    }

    @Test
    void oneFailingChannel_stillDeliversToTheOthers() {
        RecordingProvider ok = new RecordingProvider("ok", false);
        PushNotificationTool tool = new PushNotificationTool(
                List.of(new RecordingProvider("bad", true), ok));

        ToolResult result = tool.execute(input("t", "b"), context);

        assertFalse(result.isError());
        assertEquals(1, ok.sent.size());
        assertTrue(result.textContent().contains("Failed channels: bad"));
    }

    @Test
    void allChannelsFailing_isError() {
        PushNotificationTool tool = new PushNotificationTool(
                List.of(new RecordingProvider("bad", true)));

        assertTrue(tool.execute(input("t", "b"), context).isError());
    }

    @Test
    void missingTitle_isError() {
        PushNotificationTool tool = new PushNotificationTool(
                List.of(new RecordingProvider("a", false)));

        assertTrue(tool.execute(MAPPER.createObjectNode(), context).isError());
    }

    @Test
    @Timeout(15)
    void webhookProvider_postsJsonWithHeaders() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        AtomicReference<String> receivedAuth = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/notify", exchange -> {
            receivedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes()));
            exchange.sendResponseHeaders(204, -1);
        });
        server.start();
        try {
            WebhookNotificationProvider provider = new WebhookNotificationProvider(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/notify",
                    Map.of("Authorization", "Bearer tok"));

            provider.send("deploy done", "all green", "warning");

            JsonNode payload = MAPPER.readTree(receivedBody.get());
            assertEquals("deploy done", payload.path("title").asText());
            assertEquals("all green", payload.path("body").asText());
            assertEquals("warning", payload.path("level").asText());
            assertEquals("Bearer tok", receivedAuth.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void configLoader_defaultsToStdout_andParsesProviders(@TempDir Path dir) throws Exception {
        // Missing file → stdout only
        List<NotificationProvider> defaults =
                NotificationConfigLoader.load(dir.resolve("missing.json"));
        assertEquals(1, defaults.size());
        assertEquals("stdout", defaults.get(0).name());

        // Configured providers, with env substitution in the webhook URL
        Path settings = dir.resolve("settings.json");
        Files.writeString(settings, """
                {"notifications": {
                  "providers": ["stdout", "webhook", "slack"],
                  "webhookUrl": "https://example.com/${HOME}",
                  "slackWebhookUrl": "https://hooks.slack.com/services/x"
                }}
                """);

        List<NotificationProvider> providers = NotificationConfigLoader.load(settings);
        List<String> names = providers.stream().map(NotificationProvider::name).toList();
        assertEquals(List.of("stdout", "webhook", "slack"), names);
    }
}
