package dev.openclaude.core.state;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.UnaryOperator;

/**
 * Reactive state container — direct port of store.ts.
 * Thread-safe via volatile + ConcurrentHashMap.
 *
 * @param <T> the state type
 */
public final class Store<T> {

    @FunctionalInterface
    public interface Listener {
        void onChange();
    }

    @FunctionalInterface
    public interface Unsubscribe {
        void unsubscribe();
    }

    private volatile T state;
    private final Set<Listener> listeners = ConcurrentHashMap.newKeySet();
    private final BiConsumer<T, T> onChange;

    public Store(T initialState) {
        this(initialState, null);
    }

    public Store(T initialState, BiConsumer<T, T> onChange) {
        this.state = initialState;
        this.onChange = onChange;
    }

    public T getState() {
        return state;
    }

    public synchronized void setState(UnaryOperator<T> updater) {
        T prev = state;
        T next = updater.apply(prev);
        if (next == prev) return;
        state = next;
        if (onChange != null) {
            onChange.accept(next, prev);
        }
        for (Listener listener : listeners) {
            listener.onChange();
        }
    }

    public Unsubscribe subscribe(Listener listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }
}
