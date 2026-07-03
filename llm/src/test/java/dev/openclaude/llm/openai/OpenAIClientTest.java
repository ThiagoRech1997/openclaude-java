package dev.openclaude.llm.openai;

import dev.openclaude.core.model.ContentBlock;
import dev.openclaude.core.model.Message;
import dev.openclaude.core.model.StreamEvent;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpenAIClientTest {

    private List<StreamEvent> parse(String sse) {
        List<StreamEvent> events = new ArrayList<>();
        new OpenAIClient("key", "http://test").parseSseStream(
                new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)), events::add);
        return events;
    }

    @Test
    void parallelToolCalls_eachClosedBeforeNextStarts() {
        String sse = """
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_a","function":{"name":"ToolA","arguments":""}}]}}]}
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\\"x\\":1}"}}]}}]}
                data: {"choices":[{"delta":{"tool_calls":[{"index":1,"id":"call_b","function":{"name":"ToolB","arguments":""}}]}}]}
                data: {"choices":[{"delta":{"tool_calls":[{"index":1,"function":{"arguments":"{\\"y\\":2}"}}]}}]}
                data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}
                data: [DONE]
                """;

        List<StreamEvent> events = parse(sse);

        // The stop closing call_a must arrive before call_b starts, otherwise
        // downstream single-slot accumulators lose the first call
        int startB = -1, stopA = -1;
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i) instanceof StreamEvent.ToolUseStart tus && "call_b".equals(tus.id())) {
                startB = i;
            }
            if (events.get(i) instanceof StreamEvent.ContentBlockStop cbs && cbs.index() == 0) {
                stopA = i;
            }
        }
        assertTrue(stopA >= 0, "ContentBlockStop for the first tool call must be emitted");
        assertTrue(startB >= 0, "second tool call must start");
        assertTrue(stopA < startB, "first tool call must be closed before the second starts");

        // Both calls must survive into the final message
        Message.AssistantMessage msg = finalMessage(events);
        List<ContentBlock.ToolUse> tools = toolUses(msg);
        assertEquals(2, tools.size());
        assertEquals("call_a", tools.get(0).id());
        assertEquals(1, tools.get(0).input().path("x").asInt());
        assertEquals("call_b", tools.get(1).id());
        assertEquals(2, tools.get(1).input().path("y").asInt());
        assertEquals("tool_use", msg.stopReason());
    }

    @Test
    void usageChunkWithEmptyChoices_isNotDiscarded() {
        String sse = """
                data: {"choices":[{"delta":{"content":"hello"}}]}
                data: {"choices":[{"delta":{},"finish_reason":"stop"}]}
                data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5}}
                data: [DONE]
                """;

        List<StreamEvent> events = parse(sse);

        StreamEvent.MessageDelta md = events.stream()
                .filter(e -> e instanceof StreamEvent.MessageDelta)
                .map(e -> (StreamEvent.MessageDelta) e)
                .findFirst().orElseThrow();
        assertEquals(10, md.usage().inputTokens());
        assertEquals(5, md.usage().outputTokens());
    }

    // ---------- helpers ----------

    private static Message.AssistantMessage finalMessage(List<StreamEvent> events) {
        return events.stream()
                .filter(e -> e instanceof StreamEvent.MessageComplete)
                .map(e -> ((StreamEvent.MessageComplete) e).message())
                .findFirst().orElseThrow();
    }

    private static List<ContentBlock.ToolUse> toolUses(Message.AssistantMessage msg) {
        List<ContentBlock.ToolUse> tools = new ArrayList<>();
        for (ContentBlock block : msg.content()) {
            if (block instanceof ContentBlock.ToolUse tu) {
                tools.add(tu);
            }
        }
        return tools;
    }
}
