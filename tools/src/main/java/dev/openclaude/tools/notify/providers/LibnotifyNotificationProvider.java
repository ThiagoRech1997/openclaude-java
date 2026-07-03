package dev.openclaude.tools.notify.providers;

import dev.openclaude.tools.notify.NotificationProvider;

import java.util.concurrent.TimeUnit;

/**
 * Linux desktop notifications via {@code notify-send} (libnotify).
 */
public class LibnotifyNotificationProvider implements NotificationProvider {

    @Override
    public String name() {
        return "libnotify";
    }

    @Override
    public void send(String title, String body, String level) throws Exception {
        String urgency = switch (level) {
            case "error" -> "critical";
            case "warning" -> "normal";
            default -> "low";
        };
        Process process = new ProcessBuilder(
                "notify-send", "--urgency", urgency, "--app-name", "openclaude", title, body)
                .start();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("notify-send timed out");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("notify-send exited with " + process.exitValue());
        }
    }
}
