package dev.openclaude.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openclaude.core.hooks.HookConfig;
import dev.openclaude.core.hooks.HookDecision;
import dev.openclaude.core.hooks.HookExecutor;
import dev.openclaude.core.model.ContentBlock;
import dev.openclaude.core.model.Message;
import dev.openclaude.core.model.StreamEvent;
import dev.openclaude.core.model.Usage;
import dev.openclaude.core.permissions.PermissionManager;
import dev.openclaude.core.permissions.PermissionMode;
import dev.openclaude.llm.LlmClient;
import dev.openclaude.llm.LlmRequest;
import dev.openclaude.tools.Tool;
import dev.openclaude.tools.ToolRegistry;
import dev.openclaude.tools.ToolResult;
import dev.openclaude.tools.ToolUseContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class QueryEngineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void ask_withAllowingHandler_runsTool() {
        FakeTool tool = new FakeTool("Writer", false);
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        PermissionManager pm = new PermissionManager(PermissionMode.DEFAULT);

        List<EngineEvent> events = new ArrayList<>();
        QueryEngine engine = new QueryEngine(
                scriptedClient(toolUseThenEndTurn("t1", "Writer", "{}")),
                registry, "test-model", "sys", 1024, Path.of("."),
                events::add, null, null, pm,
                (name, input, isReadOnly) -> PermissionManager.PermissionDecision.ALLOWED
        );

        engine.run("please write");

        assertEquals(1, tool.executions.get(), "tool should have executed once");
        assertTrue(events.stream().anyMatch(e -> e instanceof EngineEvent.Done));
        EngineEvent.ToolExecutionEnd end = events.stream()
                .filter(e -> e instanceof EngineEvent.ToolExecutionEnd)
                .map(e -> (EngineEvent.ToolExecutionEnd) e)
                .findFirst().orElseThrow();
        assertFalse(end.result().isError());
    }

    @Test
    void ask_withDenyingHandler_skipsToolAndFeedsErrorToLlm() {
        FakeTool tool = new FakeTool("Writer", false);
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        PermissionManager pm = new PermissionManager(PermissionMode.DEFAULT);

        ScriptedLlmClient client = scriptedClient(toolUseThenEndTurn("t1", "Writer", "{}"));

        List<EngineEvent> events = new ArrayList<>();
        QueryEngine engine = new QueryEngine(
                client, registry, "test-model", "sys", 1024, Path.of("."),
                events::add, null, null, pm,
                (name, input, isReadOnly) -> PermissionManager.PermissionDecision.DENIED
        );

        engine.run("please write");

        assertEquals(0, tool.executions.get(), "tool must NOT execute when denied");
        assertEquals(1, pm.getDenialCount("Writer"), "denial should be recorded");

        EngineEvent.ToolExecutionEnd end = events.stream()
                .filter(e -> e instanceof EngineEvent.ToolExecutionEnd)
                .map(e -> (EngineEvent.ToolExecutionEnd) e)
                .findFirst().orElseThrow();
        assertTrue(end.result().isError());
        assertTrue(end.result().textContent().startsWith("Denied by permission policy"));

        // The second LLM call must include a ToolResult user message with the denial text
        assertEquals(2, client.capturedMessages.size(), "LLM must be called again with the denial result");
        List<Message> secondCallMessages = client.capturedMessages.get(1);
        Message lastMsg = secondCallMessages.get(secondCallMessages.size() - 1);
        assertTrue(lastMsg instanceof Message.UserMessage);
        Message.UserMessage um = (Message.UserMessage) lastMsg;
        ContentBlock.ToolResult tr = (ContentBlock.ToolResult) um.content().get(0);
        assertTrue(tr.isError());
        assertTrue(contentText(tr.content()).startsWith("Denied by permission policy"));
    }

    @Test
    void ask_withNullHandler_defaultsToDeny() {
        FakeTool tool = new FakeTool("Writer", false);
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        PermissionManager pm = new PermissionManager(PermissionMode.DEFAULT);

        List<EngineEvent> events = new ArrayList<>();
        QueryEngine engine = new QueryEngine(
                scriptedClient(toolUseThenEndTurn("t1", "Writer", "{}")),
                registry, "test-model", "sys", 1024, Path.of("."),
                events::add, null, null, pm, null
        );

        engine.run("please write");

        assertEquals(0, tool.executions.get(), "tool must NOT execute without a handler");
        assertTrue(events.stream()
                .filter(e -> e instanceof EngineEvent.ToolExecutionEnd)
                .map(e -> ((EngineEvent.ToolExecutionEnd) e).result().isError())
                .findFirst().orElse(false));
    }

    @Test
    void alwaysAllow_bypassesHandler() {
        FakeTool tool = new FakeTool("Writer", false);
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        PermissionManager pm = new PermissionManager(PermissionMode.DEFAULT);
        pm.addAlwaysAllow("Writer");

        AtomicInteger handlerCalls = new AtomicInteger();
        QueryEngine engine = new QueryEngine(
                scriptedClient(toolUseThenEndTurn("t1", "Writer", "{}")),
                registry, "test-model", "sys", 1024, Path.of("."),
                e -> {}, null, null, pm,
                (name, input, isReadOnly) -> {
                    handlerCalls.incrementAndGet();
                    return PermissionManager.PermissionDecision.DENIED;
                }
        );

        engine.run("please write");

        assertEquals(1, tool.executions.get(), "tool executes because it's in alwaysAllow");
        assertEquals(0, handlerCalls.get(), "handler must not be consulted when alwaysAllow hits");
    }

    @Test
    void readOnlyTool_skipsHandler() {
        FakeTool tool = new FakeTool("Reader", true);
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        PermissionManager pm = new PermissionManager(PermissionMode.DEFAULT);

        AtomicInteger handlerCalls = new AtomicInteger();
        QueryEngine engine = new QueryEngine(
                scriptedClient(toolUseThenEndTurn("t1", "Reader", "{}")),
                registry, "test-model", "sys", 1024, Path.of("."),
                e -> {}, null, null, pm,
                (name, input, isReadOnly) -> {
                    handlerCalls.incrementAndGet();
                    return PermissionManager.PermissionDecision.ALLOWED;
                }
        );

        engine.run("please read");

        assertEquals(1, tool.executions.get());
        assertEquals(0, handlerCalls.get(), "read-only tool in DEFAULT mode is auto-allowed");
    }

    @Test
    void hookDeny_shortCircuitsBeforePermissionHandler() {
        FakeTool tool = new FakeTool("Writer", false);
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        PermissionManager pm = new PermissionManager(PermissionMode.DEFAULT);

        AtomicInteger handlerCalls = new AtomicInteger();
        HookExecutor denyingHook = new HookExecutor(HookConfig.empty(), "s", Path.of(".")) {
            @Override public boolean isEmpty() { return false; }
            @Override public HookDecision runPreToolUse(String toolName, JsonNode input) {
                return new HookDecision.Deny("nope");
            }
        };

        QueryEngine engine = new QueryEngine(
                scriptedClient(toolUseThenEndTurn("t1", "Writer", "{}")),
                registry, "test-model", "sys", 1024, Path.of("."),
                e -> {}, null, denyingHook, pm,
                (name, input, isReadOnly) -> {
                    handlerCalls.incrementAndGet();
                    return PermissionManager.PermissionDecision.ALLOWED;
                }
        );

        engine.run("please write");

        assertEquals(0, tool.executions.get(), "tool must NOT execute when hook denies");
        assertEquals(0, handlerCalls.get(), "permission handler must NOT be consulted after hook deny");
    }

    @Test
    void noPermissions_allowsAll() {
        FakeTool tool = new FakeTool("Writer", false);
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);

        QueryEngine engine = new QueryEngine(
                scriptedClient(toolUseThenEndTurn("t1", "Writer", "{}")),
                registry, "test-model", "sys", 1024, Path.of("."),
                e -> {}, null, null, null, null
        );

        engine.run("please write");

        assertEquals(1, tool.executions.get(),
                "when no PermissionManager is wired, engine falls through and tool executes");
    }

    // ---------- helpers ----------

    private static String contentText(List<ContentBlock> blocks) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : blocks) {
            if (b instanceof ContentBlock.Text t) sb.append(t.text());
        }
        return sb.toString();
    }

    /**
     * Script two responses: first emits a single tool_use, second emits a plain
     * text message with end_turn (so the loop terminates).
     */
    private static List<List<StreamEvent>> toolUseThenEndTurn(String id, String name, String inputJson) {
        List<StreamEvent> first = List.of(
                new StreamEvent.MessageStart("m1", "test", Usage.ZERO),
                new StreamEvent.ToolUseStart(id, name),
                new StreamEvent.ToolInputDelta(inputJson),
                new StreamEvent.ContentBlockStop(0),
                new StreamEvent.MessageDelta("tool_use", new Usage(1, 1, 0, 0))
        );
        List<StreamEvent> second = List.of(
                new StreamEvent.MessageStart("m2", "test", Usage.ZERO),
                new StreamEvent.TextDelta("all done"),
                new StreamEvent.ContentBlockStop(0),
                new StreamEvent.MessageDelta("end_turn", new Usage(1, 1, 0, 0))
        );
        return List.of(first, second);
    }

    private static ScriptedLlmClient scriptedClient(List<List<StreamEvent>> responses) {
        return new ScriptedLlmClient(responses);
    }

    private static final class ScriptedLlmClient implements LlmClient {
        private final List<List<StreamEvent>> responses;
        /** Snapshots of the messages list at each call (engine reuses the list, so we copy). */
        final List<List<Message>> capturedMessages = new ArrayList<>();
        private int call = 0;

        ScriptedLlmClient(List<List<StreamEvent>> responses) {
            this.responses = responses;
        }

        @Override
        public void streamMessage(LlmRequest request, Consumer<StreamEvent> eventHandler) {
            capturedMessages.add(List.copyOf(request.messages()));
            List<StreamEvent> events = responses.get(Math.min(call, responses.size() - 1));
            call++;
            for (StreamEvent e : events) {
                eventHandler.accept(e);
            }
        }

        @Override
        public String providerName() {
            return "test";
        }
    }

    private static final class FakeTool implements Tool {
        final AtomicInteger executions = new AtomicInteger();
        private final String name;
        private final boolean readOnly;

        FakeTool(String name, boolean readOnly) {
            this.name = name;
            this.readOnly = readOnly;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "fake tool";
        }

        @Override
        public JsonNode inputSchema() {
            return MAPPER.createObjectNode();
        }

        @Override
        public boolean isReadOnly() {
            return readOnly;
        }

        @Override
        public ToolResult execute(JsonNode input, ToolUseContext context) {
            executions.incrementAndGet();
            return ToolResult.success("ok");
        }
    }
}
