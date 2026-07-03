package dev.openclaude.core.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.openclaude.core.model.Message;
import dev.openclaude.core.model.Usage;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
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

    private SessionManager(String sessionId, Instant startTime, int turnCount,
                           Usage totalUsage, List<Message> restoredMessages) {
        this.sessionId = sessionId;
        this.startTime = startTime;
        this.turnCount = turnCount;
        this.totalUsage = totalUsage;
        this.messages.addAll(restoredMessages);
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
    }

    /**
     * Full reset: conversation, usage, and turn counters.
     */
    public void reset() {
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
     * Save the full session (metadata + conversation) to disk for later resumption.
     * Written atomically (temp file + move) so a crash mid-write never corrupts
     * the previous snapshot.
     */
    public void save(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path sessionFile = directory.resolve("session-" + sessionId + ".json");

        ObjectNode root = MAPPER.createObjectNode();
        root.put("version", 1);
        root.put("sessionId", sessionId);
        root.put("startTime", startTime.toString());
        root.put("turnCount", turnCount);
        root.set("usage", SessionCodec.usageToJson(totalUsage));
        ArrayNode messagesArray = root.putArray("messages");
        for (Message message : messages) {
            messagesArray.add(SessionCodec.messageToJson(message));
        }

        Path tmp = sessionFile.resolveSibling(sessionFile.getFileName() + ".tmp");
        Files.writeString(tmp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        try {
            Files.move(tmp, sessionFile,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, sessionFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Load a previously saved session, restoring the conversation, usage, and
     * turn counters. Subsequent {@link #save} calls overwrite the same file.
     */
    public static SessionManager load(Path sessionFile) throws IOException {
        JsonNode root = MAPPER.readTree(Files.readString(sessionFile));
        String id = root.path("sessionId").asText("");
        if (id.isBlank()) {
            throw new IOException("Invalid session file (missing sessionId): " + sessionFile);
        }

        Instant start;
        try {
            start = Instant.parse(root.path("startTime").asText(""));
        } catch (Exception e) {
            start = Instant.now();
        }

        List<Message> restored = new ArrayList<>();
        for (JsonNode m : root.path("messages")) {
            restored.add(SessionCodec.messageFromJson(m));
        }

        return new SessionManager(id, start, root.path("turnCount").asInt(0),
                SessionCodec.usageFromJson(root.get("usage")), restored);
    }

    /**
     * Most recently modified session file in the directory, if any.
     */
    public static Optional<Path> latestSessionFile(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return Optional.empty();
        }
        try (var files = Files.list(directory)) {
            return files
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("session-") && name.endsWith(".json");
                    })
                    .max(Comparator.comparingLong(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis();
                        } catch (IOException e) {
                            return 0L;
                        }
                    }));
        }
    }

    /**
     * Get the session directory path (~/.claude/sessions/).
     */
    public static Path getSessionDirectory() {
        String home = System.getProperty("user.home");
        return Path.of(home, ".claude", "sessions");
    }
}
