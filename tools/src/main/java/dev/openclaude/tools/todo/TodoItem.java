package dev.openclaude.tools.todo;

/**
 * A single todo list item tracked by {@link TodoStore} and manipulated by
 * {@link TodoWriteTool}. Mirrors the shape of Claude Code's TodoWrite entries.
 */
public record TodoItem(String content, String activeForm, String status) {

    public static final String PENDING = "pending";
    public static final String IN_PROGRESS = "in_progress";
    public static final String COMPLETED = "completed";

    public static boolean isValidStatus(String status) {
        return PENDING.equals(status) || IN_PROGRESS.equals(status) || COMPLETED.equals(status);
    }
}
