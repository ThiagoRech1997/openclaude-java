package dev.openclaude.tools.background;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages background processes started by BashTool.
 * Captures stdout line-by-line into a buffer that can be drained by MonitorTool.
 */
public final class BackgroundProcessManager {

    public record BackgroundProcess(
            String id,
            String command,
            Process process,
            ConcurrentLinkedQueue<String> outputBuffer,
            Thread readerThread
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
     * Starts a daemon thread that reads stdout line-by-line into a buffer.
     *
     * @return the process ID (e.g., "bg_1")
     */
    public String startProcess(Process process, String command) {
        String id = "bg_" + idCounter.incrementAndGet();
        ConcurrentLinkedQueue<String> buffer = new ConcurrentLinkedQueue<>();

        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    buffer.add(line);
                }
            } catch (Exception e) {
                buffer.add("[reader error: " + e.getMessage() + "]");
            }
        }, "bg-process-reader-" + id);
        reader.setDaemon(true);
        reader.start();

        processes.put(id, new BackgroundProcess(id, command, process, buffer, reader));
        return id;
    }

    /**
     * Drain all buffered output lines since the last call.
     *
     * @return list of new lines, empty if none
     */
    public List<String> drainOutput(String processId) {
        BackgroundProcess bp = processes.get(processId);
        if (bp == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        String line;
        while ((line = bp.outputBuffer().poll()) != null) {
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
