package dev.openclaude.tools.todo;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TodoStoreTest {

    @Test
    void startsEmpty() {
        assertTrue(new TodoStore().list().isEmpty());
    }

    @Test
    void setReplacesContent() {
        TodoStore store = new TodoStore();
        store.set(List.of(new TodoItem("a", "doing a", TodoItem.PENDING)));
        assertEquals(1, store.list().size());

        store.set(List.of(
                new TodoItem("b", "doing b", TodoItem.IN_PROGRESS),
                new TodoItem("c", "doing c", TodoItem.COMPLETED)));
        assertEquals(2, store.list().size());
        assertEquals("b", store.list().get(0).content());
    }

    @Test
    void snapshotIsImmutable() {
        TodoStore store = new TodoStore();
        store.set(List.of(new TodoItem("a", "doing a", TodoItem.PENDING)));
        List<TodoItem> snapshot = store.list();
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.add(new TodoItem("x", "x", TodoItem.PENDING)));
    }

    @Test
    void setCopiesCallerList() {
        TodoStore store = new TodoStore();
        List<TodoItem> mutable = new ArrayList<>();
        mutable.add(new TodoItem("a", "doing a", TodoItem.PENDING));
        store.set(mutable);

        mutable.add(new TodoItem("b", "doing b", TodoItem.PENDING));
        assertEquals(1, store.list().size(), "caller mutation must not leak into the store");
    }
}
