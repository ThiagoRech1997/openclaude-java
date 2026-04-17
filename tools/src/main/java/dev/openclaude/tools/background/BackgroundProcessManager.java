package dev.openclaude.tools.background;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages background processes started by BashTool.
 * Captures stdout line-by-line into a bounded buffer that can be drained by MonitorTool.
 */
public final class BackgroundProcessManager {

    static final int MAX_BUFFER_LINES = 10_000;
    private static final String WRAPPED_MARKER = "[buffer wrapped — older output dropped]";

    public record BackgroundProcess(
            String id,
            String command,
            Process process,
            LinkedBlockingDeque<String> outputBuffer,
            Thread readerThread,
            AtomicBoolean wrapSinceLastDrain
    ) {
        public boolean isRunning() {
            return process.isAlive();
        }

        public int exitCode() {
            return process.exitValue();
        }
    }

    private final AtomicInteger idCounter = new AtomicInteger(0);
    private final ConcurrentHashMap<String, BackgroundProcess> processes = new ConcurrentHashMap<>();

    /**
     * Register a process for background tracking.
     * Starts a daemon thread that reads stdout line-by-line into a bounded buffer.
     * When the buffer is full, the oldest line is dropped and a wrap flag is set; the
     * next drain prepends a one-line marker noting that output was lost.
     *
     * @return the process ID (e.g., "bg_1")
     */
    public String startProcess(Process process, String command) {
        String id = "bg_" + idCounter.incrementAndGet();
        LinkedBlockingDeque<String> buffer = new LinkedBlockingDeque<>(MAX_BUFFER_LINES);
        AtomicBoolean wrapFlag = new AtomicBoolean(false);

        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    appendBounded(buffer, wrapFlag, line);
                }
            } catch (Exception e) {
                appendBounded(buffer, wrapFlag, "[reader error: " + e.getMessage() + "]");
            }
        }, "bg-process-reader-" + id);
        reader.setDaemon(true);
        reader.start();

        processes.put(id, new BackgroundProcess(id, command, process, buffer, reader, wrapFlag));
        return id;
    }

    private static void appendBounded(LinkedBlockingDeque<String> buffer, AtomicBoolean wrapFlag, String line) {
        while (!buffer.offerLast(line)) {
            buffer.pollFirst();
            wrapFlag.set(true);
        }
    }

    /**
     * Drain all buffered output lines since the last call.
     * If the buffer dropped lines since the previous drain, the returned list is prefixed
     * with a single wrap marker.
     */
    public List<String> drainOutput(String processId) {
        BackgroundProcess bp = processes.get(processId);
        if (bp == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        if (bp.wrapSinceLastDrain().compareAndSet(true, false)) {
            lines.add(WRAPPED_MARKER);
        }
        String line;
        while ((line = bp.outputBuffer().pollFirst()) != null) {
            lines.add(line);
        }
        return lines;
    }

    /**
     * Get process info, or null if not found.
     */
    public BackgroundProcess getProcess(String processId) {
        return processes.get(processId);
    }

    /**
     * List all tracked background processes.
     */
    public Collection<BackgroundProcess> listProcesses() {
        return processes.values();
    }

    /**
     * Attempt to terminate a background process. Tries graceful {@code destroy()} first,
     * then {@code destroyForcibly()} if the process does not exit within ~1 second.
     *
     * @return true if a running process was found and signalled, false if unknown or already exited.
     */
    public boolean killProcess(String processId) {
        BackgroundProcess bp = processes.get(processId);
        if (bp == null || !bp.isRunning()) {
            return false;
        }
        Process p = bp.process();
        p.destroy();
        try {
            if (!p.waitFor(1, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
        return true;
    }

    /**
     * Remove a process entry from the registry. Safe to call whether running or exited.
     */
    public void removeProcess(String processId) {
        processes.remove(processId);
    }

    /**
     * Destroy all tracked background processes.
     */
    public void shutdown() {
        for (BackgroundProcess bp : processes.values()) {
            if (bp.isRunning()) {
                bp.process().destroyForcibly();
            }
        }
        processes.clear();
    }
}
