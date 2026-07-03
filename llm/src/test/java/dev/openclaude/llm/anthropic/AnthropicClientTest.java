package dev.openclaude.llm.anthropic;

import dev.openclaude.core.model.StreamEvent;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnthropicClientTest {

    @Test
    void messageDeltaUsage_isCumulative_notAddedOnTopOfMessageStart() {
        String sse = """
                event: message_start
                data: {"type":"message_start","message":{"id":"m1","model":"c","usage":{"input_tokens":100,"output_tokens":2}}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":50}}

                event: message_stop
                data: {"type":"message_stop"}
                """;

        List<StreamEvent> events = new ArrayList<>();
        new AnthropicClient("key", "http://test").parseSseStream(
                new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)), events::add);

        StreamEvent.MessageDelta md = events.stream()
                .filter(e -> e instanceof StreamEvent.MessageDelta)
                .map(e -> (StreamEvent.MessageDelta) e)
                .findFirst().orElseThrow();

        assertEquals(100, md.usage().inputTokens());
        // message_delta output_tokens (50) is the cumulative total — the initial
        // output_tokens from message_start (2) must not be added on top
        assertEquals(50, md.usage().outputTokens());

        // The final message must carry the streamed text, not just tool blocks
        var complete = events.stream()
                .filter(e -> e instanceof StreamEvent.MessageComplete)
                .map(e -> (StreamEvent.MessageComplete) e)
                .findFirst().orElseThrow();
        boolean hasText = complete.message().content().stream()
                .anyMatch(b -> b instanceof dev.openclaude.core.model.ContentBlock.Text t
                        && t.text().equals("hi"));
        assertTrue(hasText, "MessageComplete must include the streamed text block");
    }
}
