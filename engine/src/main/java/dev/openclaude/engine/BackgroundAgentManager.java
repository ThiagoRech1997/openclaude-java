package dev.openclaude.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Manages background sub-agent execution.
 * Agents run in a thread pool and results are queued for polling by the main engine loop.
 */
public class BackgroundAgentManager {

    /**
     * Result from a completed background agent.
     */
    public record CompletedAgent(String description, String result, boolean isError) {}

    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "background-agent");
        t.setDaemon(true);
        return t;
    });

    private final ConcurrentLinkedQueue<CompletedAgent> completedQueue = new ConcurrentLinkedQueue<>();

    /**
     * Submit a background agent task.
     *
     * @param description short description of the agent's task
     * @param task        runnable that returns the agent result via the callback
     */
    public void submit(String description, Callable<String> task) {
        executor.submit(() -> {
            try {
                String result = task.call();
                completedQueue.add(new CompletedAgent(description, result, false));
            } catch (Exception e) {
                completedQueue.add(new CompletedAgent(description,
                        "Background agent failed: " + e.getMessage(), true));
            }
        });
    }

    /**
     * Poll and drain all completed background agent results.
     *
     * @return list of completed results (empty if none ready)
     */
    public List<CompletedAgent> pollCompleted() {
        List<CompletedAgent> results = new ArrayList<>();
        CompletedAgent item;
        while ((item = completedQueue.poll()) != null) {
            results.add(item);
        }
        return results;
    }

    /**
     * Shut down the executor. Call on application exit.
     */
    public void shutdown() {
        executor.shutdownNow();
    }
}
