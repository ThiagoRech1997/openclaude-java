package dev.openclaude.tools.notify;

/**
 * A channel that can deliver a notification to the user (stdout, desktop,
 * webhook, Slack, ...).
 */
public interface NotificationProvider {

    /** Short identifier used in config (`stdout`, `libnotify`, `webhook`, `slack`). */
    String name();

    /**
     * Deliver the notification.
     *
     * @param title short title
     * @param body  message body
     * @param level "info", "warning", or "error"
     */
    void send(String title, String body, String level) throws Exception;
}
