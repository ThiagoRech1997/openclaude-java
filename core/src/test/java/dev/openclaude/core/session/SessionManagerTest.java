package dev.openclaude.core.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openclaude.core.model.ContentBlock;
import dev.openclaude.core.model.Message;
import dev.openclaude.core.model.Usage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SessionManagerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void saveAndLoad_roundTripsFullConversation() throws Exception {
        SessionManager session = new SessionManager();
        session.addMessage(new Message.UserMessage("list the files"));
        session.addMessage(new Message.AssistantMessage(List.of(
                new ContentBlock.Thinking("let me look"),
                new ContentBlock.Text("Sure, running ls."),
                new ContentBlock.ToolUse("t1", "Bash",
                        MAPPER.createObjectNode().put("command", "ls"))),
                "tool_use", new Usage(10, 5, 0, 0)));
        session.addMessage(new Message.UserMessage(List.of(new ContentBlock.ToolResult(
                "t1", List.of(new ContentBlock.Text("a.txt\nb.txt")), false))));
        session.addMessage(new Message.AssistantMessage(List.of(
                new ContentBlock.Text("Two files: a.txt and b.txt.")),
                "end_turn", new Usage(20, 8, 3, 7)));
        session.incrementTurn();
        session.incrementTurn();
        session.addUsage(new Usage(30, 13, 3, 7));

        session.save(tempDir);

        SessionManager loaded = SessionManager.load(
                tempDir.resolve("session-" + session.getSessionId() + ".json"));

        assertEquals(session.getSessionId(), loaded.getSessionId());
        assertEquals(2, loaded.getTurnCount());
        assertEquals(new Usage(30, 13, 3, 7), loaded.getTotalUsage());
        // Records implement equals — full structural comparison of the conversation
        assertEquals(session.getMessages(), loaded.getMessages());
    }

    @Test
    void save_overwritesSameFileOnEveryCall() throws Exception {
        SessionManager session = new SessionManager();
        session.addMessage(new Message.UserMessage("one"));
        session.save(tempDir);
        session.addMessage(new Message.UserMessage("two"));
        session.save(tempDir);

        try (var files = Files.list(tempDir)) {
            assertEquals(1, files.filter(p -> p.toString().endsWith(".json")).count());
        }
        SessionManager loaded = SessionManager.load(
                tempDir.resolve("session-" + session.getSessionId() + ".json"));
        assertEquals(2, loaded.getMessages().size());
    }

    @Test
    void load_rejectsFileWithoutSessionId() throws Exception {
        Path bogus = tempDir.resolve("session-bogus.json");
        Files.writeString(bogus, "{\"messages\":[]}");
        assertThrows(Exception.class, () -> SessionManager.load(bogus));
    }

    @Test
    void latestSessionFile_picksNewest() throws Exception {
        SessionManager first = new SessionManager();
        first.addMessage(new Message.UserMessage("old"));
        first.save(tempDir);

        Path firstFile = tempDir.resolve("session-" + first.getSessionId() + ".json");
        Files.setLastModifiedTime(firstFile,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 60_000));

        SessionManager second = new SessionManager();
        second.addMessage(new Message.UserMessage("new"));
        second.save(tempDir);

        Optional<Path> latest = SessionManager.latestSessionFile(tempDir);
        assertTrue(latest.isPresent());
        assertEquals("session-" + second.getSessionId() + ".json",
                latest.get().getFileName().toString());
    }

    @Test
    void latestSessionFile_emptyOrMissingDirectory() throws Exception {
        assertTrue(SessionManager.latestSessionFile(tempDir).isEmpty());
        assertTrue(SessionManager.latestSessionFile(tempDir.resolve("nope")).isEmpty());
    }

    @Test
    void imageBlocks_surviveRoundTrip() throws Exception {
        SessionManager session = new SessionManager();
        session.addMessage(new Message.UserMessage(List.of(new ContentBlock.ToolResult(
                "t9",
                List.of(new ContentBlock.Image("image/png", "aWJhc2U2NA==")),
                false))));
        session.save(tempDir);

        SessionManager loaded = SessionManager.load(
                tempDir.resolve("session-" + session.getSessionId() + ".json"));
        assertEquals(session.getMessages(), loaded.getMessages());
    }
}
