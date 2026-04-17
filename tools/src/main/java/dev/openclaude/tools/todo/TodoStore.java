package dev.openclaude.tools.todo;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory, thread-safe holder for the session's todo list.
 *
 * <p>A single {@link TodoStore} instance is created in
 * {@code Main.createToolRegistry()} and injected into {@link TodoWriteTool},
 * mirroring how {@code BackgroundProcessManager} is shared across bash/monitor/kill.
 * State is replaced atomically on every call; reads return an immutable snapshot.
 */
public final class TodoStore {

    private final AtomicReference<List<TodoItem>> todos = new AtomicReference<>(List.of());

    /** Replace the current list. Caller's list is defensively copied into an immutable snapshot. */
    public void set(List<TodoItem> items) {
        todos.set(List.copyOf(items));
    }

    /** Return the current immutable snapshot. */
    public List<TodoItem> list() {
        return todos.get();
    }
}
