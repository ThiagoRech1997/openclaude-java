package dev.openclaude.tools.notify.providers;

import dev.openclaude.tools.notify.NotificationProvider;

/**
 * Default provider: prints the notification to stdout.
 */
public class StdoutNotificationProvider implements NotificationProvider {

    @Override
    public String name() {
        return "stdout";
    }

    @Override
    public void send(String title, String body, String level) {
        System.out.println("[notification:" + level + "] " + title
                + (body.isBlank() ? "" : " — " + body));
    }
}
