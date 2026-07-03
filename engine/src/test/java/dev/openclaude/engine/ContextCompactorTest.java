package dev.openclaude.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openclaude.core.model.ContentBlock;
import dev.openclaude.core.model.Message;
import dev.openclaude.core.model.StreamEvent;
import dev.openclaude.core.model.Usage;
import dev.openclaude.llm.LlmClient;
import dev.openclaude.llm.LlmRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ContextCompactorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final LlmClient SUMMARIZER = new LlmClient() {
        @Override
        public void streamMessage(LlmRequest request, Consumer<StreamEvent> eventHandler) {
            eventHandler.accept(new StreamEvent.TextDelta("summary text"));
        }

        @Override
        public String providerName() {
            return "test";
        }
    };

    @Test
    void compact_neverSplitsToolUseFromToolResult() {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message.UserMessage("q1"));                         // 0
        messages.add(assistantText("a1"));                                   // 1
        messages.add(new Message.UserMessage("q2"));                         // 2
        messages.add(assistantText("a2"));                                   // 3
        messages.add(new Message.UserMessage("q3"));                         // 4
        messages.add(assistantToolUse("t1", "Bash"));                        // 5
        messages.add(toolResultMessage("t1"));                               // 6
        messages.add(assistantText("a3"));                                   // 7
        messages.add(new Message.UserMessage("q4"));                         // 8
        messages.add(assistantText("a4"));                                   // 9

        ContextCompactor compactor = new ContextCompactor(SUMMARIZER, "test-model", 4096);

        // Naive split point would be index 6 — the tool_result for the
        // tool_use at index 5. The boundary must back up to index 4.
        List<Message> compacted = compactor.compact(messages, 4);

        assertNotSame(messages, compacted, "compaction should have happened");
        // [summary user, ack assistant, q3, tool_use, tool_result, a3, q4, a4]
        assertEquals(8, compacted.size());
        assertTrue(compacted.get(2) instanceof Message.UserMessage);
        assertEquals("q3", ((ContentBlock.Text) compacted.get(2).content().get(0)).text());
        assertToolPairsIntact(compacted);
    }

    @Test
    void compact_returnsUnchangedWhenNoSafeBoundaryExists() {
        // Everything after the earliest possible boundary is tool traffic,
        // so the split point backs up to <= 2 and compaction is skipped.
        List<Message> messages = new ArrayList<>();
        messages.add(new Message.UserMessage("q1"));                         // 0
        messages.add(assistantToolUse("t1", "Bash"));                        // 1
        messages.add(toolResultMessage("t1"));                               // 2
        messages.add(assistantToolUse("t2", "Read"));                        // 3
        messages.add(toolResultMessage("t2"));                               // 4
        messages.add(assistantToolUse("t3", "Grep"));                        // 5
        messages.add(toolResultMessage("t3"));                               // 6
        messages.add(assistantText("done"));                                 // 7

        ContextCompactor compactor = new ContextCompactor(SUMMARIZER, "test-model", 4096);

        List<Message> compacted = compactor.compact(messages, 3);

        assertSame(messages, compacted);
    }

    @Test
    void compact_smallConversationIsUntouched() {
        List<Message> messages = List.of(
                new Message.UserMessage("q1"),
                assistantText("a1"));

        ContextCompactor compactor = new ContextCompactor(SUMMARIZER, "test-model", 4096);

        assertSame(messages, compactor.compact(messages, 4));
    }

    // ---------- helpers ----------

    private static Message assistantText(String text) {
        return new Message.AssistantMessage(
                List.of(new ContentBlock.Text(text)), "end_turn", Usage.ZERO);
    }

    private static Message assistantToolUse(String id, String toolName) {
        return new Message.AssistantMessage(
                List.of(new ContentBlock.ToolUse(id, toolName, MAPPER.createObjectNode())),
                "tool_use", Usage.ZERO);
    }

    private static Message toolResultMessage(String toolUseId) {
        return new Message.UserMessage(List.of(new ContentBlock.ToolResult(
                toolUseId, List.of(new ContentBlock.Text("result")), false)));
    }

    /** Every tool_result must be preceded by an assistant message containing its tool_use. */
    private static void assertToolPairsIntact(List<Message> messages) {
        for (int i = 0; i < messages.size(); i++) {
            for (ContentBlock block : messages.get(i).content()) {
                if (block instanceof ContentBlock.ToolResult tr) {
                    assertTrue(i > 0, "tool_result cannot be the first message");
                    boolean paired = messages.get(i - 1).content().stream()
                            .anyMatch(b -> b instanceof ContentBlock.ToolUse tu
                                    && tu.id().equals(tr.toolUseId()));
                    assertTrue(paired, "tool_result " + tr.toolUseId()
                            + " at index " + i + " has no preceding tool_use");
                }
            }
        }
    }
}
