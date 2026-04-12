package dev.openclaude.tui.widget;

import dev.openclaude.tui.Ansi;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Animated terminal spinner that runs in a background thread.
 */
public class Spinner {

    private static final String[] FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private static final long FRAME_INTERVAL_MS = 80;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<String> message = new AtomicReference<>("");
    private Thread thread;
    private final java.io.PrintWriter writer;

    public Spinner(java.io.PrintWriter writer) {
        this.writer = writer;
    }

    public void start(String msg) {
        message.set(msg);
        if (running.compareAndSet(false, true)) {
            thread = new Thread(this::animate, "spinner");
            thread.setDaemon(true);
            thread.start();
        }
    }

    public void update(String msg) {
        message.set(msg);
    }

    public void stop() {
        running.set(false);
        if (thread != null) {
            try {
                thread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
        // Clear the spinner line
        writer.print("\r" + Ansi.CLEAR_LINE);
        writer.flush();
    }

    private void animate() {
        int frameIdx = 0;
        while (running.get()) {
            String frame = FRAMES[frameIdx % FRAMES.length];
            String msg = message.get();
            writer.print("\r" + Ansi.CLEAR_LINE + Ansi.CYAN + frame + " " + Ansi.RESET + Ansi.DIM + msg + Ansi.RESET);
            writer.flush();
            frameIdx++;
            try {
                Thread.sleep(FRAME_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
