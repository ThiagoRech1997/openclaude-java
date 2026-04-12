package dev.openclaude.core.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.openclaude.core.model.Message;
import dev.openclaude.core.model.Usage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Manages conversation session state: messages, usage tracking, and persistence.
 */
public class SessionManager {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String sessionId;
    private final List<Message> messages = new ArrayList<>();
    private Usage totalUsage = Usage.ZERO;
    private final Instant startTime;
    private int turnCount = 0;

    public SessionManager() {
        this.sessionId = UUID.randomUUID().toString().substring(0, 8);
        this.startTime = Instant.now();
    }

    public String getSessionId() {
        return sessionId;
    }

    public void addMessage(Message message) {
        messages.add(message);
    }

    public void addMessages(List<Message> msgs) {
        messages.addAll(msgs);
    }

    public List<Message> getMessages() {
        return Collections.unmodifiableList(messages);
    }

    public void clearMessages() {
        messages.clear();
        totalUsage = Usage.ZERO;
        turnCount = 0;
    }

    public void addUsage(Usage usage) {
        if (usage != null) {
            totalUsage = totalUsage.add(usage);
        }
    }

    public Usage getTotalUsage() {
        return totalUsage;
    }

    public void incrementTurn() {
        turnCount++;
    }

    public int getTurnCount() {
        return turnCount;
    }

    public Instant getStartTime() {
        return startTime;
    }

    /**
     * Estimate the total cost in USD based on token usage.
     * Uses rough per-token pricing for common models.
     */
    public double estimateCostUsd() {
        // Rough pricing (Claude Sonnet 4): $3/M input, $15/M output
        double inputCost = totalUsage.inputTokens() * 3.0 / 1_000_000;
        double outputCost = totalUsage.outputTokens() * 15.0 / 1_000_000;
        double cacheCost = totalUsage.cacheReadInputTokens() * 0.30 / 1_000_000;
        return inputCost + outputCost + cacheCost;
    }

    /**
     * Save session to disk for later resumption.
     */
    public void save(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path sessionFile = directory.resolve("session-" + sessionId + ".json");

        ObjectNode root = MAPPER.createObjectNode();
        root.put("sessionId", sessionId);
        root.put("startTime", startTime.toString());
        root.put("turnCount", turnCount);
        root.put("inputTokens", totalUsage.inputTokens());
        root.put("outputTokens", totalUsage.outputTokens());
        root.put("messageCount", messages.size());

        Files.writeString(sessionFile, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
    }

    /**
     * Get the session directory path (~/.claude/sessions/).
     */
    public static Path getSessionDirectory() {
        String home = System.getProperty("user.home");
        return Path.of(home, ".claude", "sessions");
    }
}
