package dev.openclaude.llm.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openclaude.core.model.ContentBlock;
import dev.openclaude.core.model.Message;
import dev.openclaude.core.model.StreamEvent;
import dev.openclaude.core.model.Usage;
import dev.openclaude.llm.LlmRequest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OllamaClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parallelToolCalls_eachClosedBeforeNextStarts() {
        String jsonl = """
                {"message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"ToolA","arguments":{"x":1}}},{"function":{"name":"ToolB","arguments":{"y":2}}}]},"done":false}
                {"message":{"role":"assistant","content":""},"done":true,"prompt_eval_count":7,"eval_count":3}
                """;

        List<StreamEvent> events = new ArrayList<>();
        new OllamaClient("http://test").parseJsonlStream(
                new ByteArrayInputStream(jsonl.getBytes(StandardCharsets.UTF_8)), events::add);

        // Sequence must be Start(A), ..., Stop(0), Start(B), ..., Stop(1)
        List<Integer> startIdx = new ArrayList<>();
        List<Integer> stopIdx = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i) instanceof StreamEvent.ToolUseStart) startIdx.add(i);
            if (events.get(i) instanceof StreamEvent.ContentBlockStop) stopIdx.add(i);
        }
        assertEquals(2, startIdx.size());
        assertEquals(2, stopIdx.size());
        assertTrue(stopIdx.get(0) < startIdx.get(1),
                "first tool call must be closed before the second starts");

        Message.AssistantMessage msg = events.stream()
                .filter(e -> e instanceof StreamEvent.MessageComplete)
                .map(e -> ((StreamEvent.MessageComplete) e).message())
                .findFirst().orElseThrow();
        long toolCount = msg.content().stream()
                .filter(b -> b instanceof ContentBlock.ToolUse).count();
        assertEquals(2, toolCount);
        assertEquals("tool_use", msg.stopReason());
        assertEquals(new Usage(7, 3, 0, 0), msg.usage());
    }

    @Test
    void buildRequestBody_replaysToolUseAndToolResults() throws Exception {
        List<Message> messages = List.of(
                new Message.UserMessage("list the files"),
                new Message.AssistantMessage(List.of(
                        new ContentBlock.Text("ok, listing"),
                        new ContentBlock.ToolUse("id1", "Bash",
                                MAPPER.createObjectNode().put("command", "ls"))),
                        "tool_use", Usage.ZERO),
                new Message.UserMessage(List.of(new ContentBlock.ToolResult(
                        "id1", List.of(new ContentBlock.Text("a.txt")), false)))
        );
        LlmRequest request = new LlmRequest("llama3", "", messages, 512);

        JsonNode body = MAPPER.readTree(new OllamaClient("http://test").buildRequestBody(request));
        JsonNode msgs = body.get("messages");

        assertEquals(3, msgs.size());
        assertEquals("user", msgs.get(0).path("role").asText());

        JsonNode asst = msgs.get(1);
        assertEquals("assistant", asst.path("role").asText());
        assertEquals("ok, listing", asst.path("content").asText());
        assertEquals("Bash", asst.path("tool_calls").get(0).path("function").path("name").asText());
        assertEquals("ls", asst.path("tool_calls").get(0).path("function")
                .path("arguments").path("command").asText());

        JsonNode tool = msgs.get(2);
        assertEquals("tool", tool.path("role").asText());
        assertEquals("a.txt", tool.path("content").asText());
    }
}
