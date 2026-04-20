package dev.openclaude.core.hooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class HookExecutorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static HookConfig configFor(HookEvent event, String matcher, String command, int timeout) {
        Pattern p = matcher == null || matcher.isEmpty() ? null : Pattern.compile(matcher);
        return new HookConfig(Map.of(event, List.of(
                new HookConfig.HookMatcher(p, List.of(
                        new HookConfig.HookCommand("command", command, timeout))))));
    }

    private static JsonNode input() {
        return MAPPER.createObjectNode().put("path", "/tmp/x");
    }

    private HookExecutor executor(HookConfig cfg) {
        return new HookExecutor(cfg, "test-session", Path.of("/tmp"));
    }

    @Test
    void preToolUse_emptyStdoutAndExitZero_allow() {
        HookExecutor hooks = executor(configFor(HookEvent.PRE_TOOL_USE, null, "true", 5));
        HookDecision d = hooks.runPreToolUse("Bash", input());
        assertInstanceOf(HookDecision.Allow.class, d);
        assertNull(((HookDecision.Allow) d).additionalContext());
    }

    @Test
    void preToolUse_jsonApprove_allow() {
        HookExecutor hooks = executor(configFor(HookEvent.PRE_TOOL_USE, null,
                "echo '{\"decision\":\"approve\"}'", 5));
        assertInstanceOf(HookDecision.Allow.class, hooks.runPreToolUse("Bash", input()));
    }

    @Test
    void preToolUse_jsonBlock_deny() {
        HookExecutor hooks = executor(configFor(HookEvent.PRE_TOOL_USE, null,
                "echo '{\"decision\":\"block\",\"reason\":\"nope\"}'", 5));
        HookDecision d = hooks.runPreToolUse("Bash", input());
        assertInstanceOf(HookDecision.Deny.class, d);
        assertEquals("nope", ((HookDecision.Deny) d).reason());
    }

    @Test
    void preToolUse_nonZeroExit_denyWithStderr() {
        HookExecutor hooks = executor(configFor(HookEvent.PRE_TOOL_USE, null,
                "echo forbidden >&2; exit 2", 5));
        HookDecision d = hooks.runPreToolUse("Bash", input());
        assertInstanceOf(HookDecision.Deny.class, d);
        assertTrue(((HookDecision.Deny) d).reason().contains("forbidden"));
    }

    @Test
    void preToolUse_replaceToolInput() {
        HookExecutor hooks = executor(configFor(HookEvent.PRE_TOOL_USE, null,
                "echo '{\"tool_input\":{\"path\":\"/replaced\"}}'", 5));
        HookDecision d = hooks.runPreToolUse("Bash", input());
        assertInstanceOf(HookDecision.ReplaceInput.class, d);
        JsonNode newInput = ((HookDecision.ReplaceInput) d).newInput();
        assertEquals("/replaced", newInput.path("path").asText());
    }

    @Test
    void preToolUse_hookSpecificOutputDeny() {
        HookExecutor hooks = executor(configFor(HookEvent.PRE_TOOL_USE, null,
                "echo '{\"hookSpecificOutput\":{\"permissionDecision\":\"deny\",\"permissionDecisionReason\":\"no Bash\"}}'", 5));
        HookDecision d = hooks.runPreToolUse("Bash", input());
        assertInstanceOf(HookDecision.Deny.class, d);
        assertEquals("no Bash", ((HookDecision.Deny) d).reason());
    }

    @Test
    void preToolUse_continueFalse_stop() {
        HookExecutor hooks = executor(configFor(HookEvent.PRE_TOOL_USE, null,
                "echo '{\"continue\":false,\"stopReason\":\"kill session\"}'", 5));
        HookDecision d = hooks.runPreToolUse("Bash", input());
        assertInstanceOf(HookDecision.Stop.class, d);
        assertEquals("kill session", ((HookDecision.Stop) d).stopReason());
    }

    @Test
    void postToolUse_blockIsDowngradedToAllowWithContext() {
        HookExecutor hooks = executor(configFor(HookEvent.POST_TOOL_USE, null,
                "echo '{\"decision\":\"block\",\"reason\":\"too slow\"}'", 5));
        HookDecision d = hooks.runPostToolUse("Bash", input(), "output", false);
        assertInstanceOf(HookDecision.Allow.class, d);
        assertEquals("too slow", ((HookDecision.Allow) d).additionalContext());
    }

    @Test
    void postToolUse_nonZeroExit_isDowngradedToAllow() {
        HookExecutor hooks = executor(configFor(HookEvent.POST_TOOL_USE, null,
                "echo boom >&2; exit 3", 5));
        HookDecision d = hooks.runPostToolUse("Bash", input(), "x", false);
        assertInstanceOf(HookDecision.Allow.class, d);
    }

    @Test
    void userPromptSubmit_additionalContext() {
        HookExecutor hooks = executor(configFor(HookEvent.USER_PROMPT_SUBMIT, null,
                "echo '{\"hookSpecificOutput\":{\"additionalContext\":\"user is admin\"}}'", 5));
        HookDecision d = hooks.runUserPromptSubmit("do the thing");
        assertInstanceOf(HookDecision.Allow.class, d);
        assertEquals("user is admin", ((HookDecision.Allow) d).additionalContext());
    }

    @Test
    void timeout_deniesForPreToolUse() {
        HookExecutor hooks = executor(configFor(HookEvent.PRE_TOOL_USE, null, "sleep 5", 1));
        HookDecision d = hooks.runPreToolUse("Bash", input());
        assertInstanceOf(HookDecision.Deny.class, d);
        assertTrue(((HookDecision.Deny) d).reason().contains("timed out"));
    }

    @Test
    void malformedStdoutWithExitZero_fallsBackToAllow() {
        HookExecutor hooks = executor(configFor(HookEvent.PRE_TOOL_USE, null,
                "echo not json at all", 5));
        HookDecision d = hooks.runPreToolUse("Bash", input());
        assertInstanceOf(HookDecision.Allow.class, d);
    }

    @Test
    void matcherRegex_onlyMatchingMatcherRuns() {
        HookConfig cfg = new HookConfig(Map.of(HookEvent.PRE_TOOL_USE, List.of(
                new HookConfig.HookMatcher(Pattern.compile("Bash"), List.of(
                        new HookConfig.HookCommand("command",
                                "echo '{\"decision\":\"block\",\"reason\":\"bash-only\"}'", 5))),
                new HookConfig.HookMatcher(Pattern.compile("Edit"), List.of(
                        new HookConfig.HookCommand("command",
                                "echo '{\"decision\":\"block\",\"reason\":\"edit-only\"}'", 5)))
        )));
        HookExecutor hooks = new HookExecutor(cfg, "s", Path.of("/tmp"));

        HookDecision bash = hooks.runPreToolUse("Bash", input());
        assertEquals("bash-only", ((HookDecision.Deny) bash).reason());

        HookDecision read = hooks.runPreToolUse("Read", input());
        assertInstanceOf(HookDecision.Allow.class, read, "Read should match neither matcher");
    }

    @Test
    void matchAllWhenMatcherNull() {
        HookExecutor hooks = executor(configFor(HookEvent.PRE_TOOL_USE, null,
                "echo '{\"decision\":\"approve\"}'", 5));
        assertInstanceOf(HookDecision.Allow.class, hooks.runPreToolUse("Bash", input()));
        assertInstanceOf(HookDecision.Allow.class, hooks.runPreToolUse("Read", input()));
    }

    @Test
    void firstDenyShortCircuits(@TempDir Path dir) throws Exception {
        // Second hook writes a sentinel file — we assert it was NOT created.
        Path sentinel = dir.resolve("second-ran");
        HookConfig cfg = new HookConfig(Map.of(HookEvent.PRE_TOOL_USE, List.of(
                new HookConfig.HookMatcher(null, List.of(
                        new HookConfig.HookCommand("command",
                                "echo '{\"decision\":\"block\",\"reason\":\"first\"}'", 5),
                        new HookConfig.HookCommand("command",
                                "touch " + sentinel, 5)))
        )));
        HookExecutor hooks = new HookExecutor(cfg, "s", Path.of("/tmp"));
        hooks.runPreToolUse("Bash", input());
        assertFalse(Files.exists(sentinel), "second hook must not run after first Deny");
    }

    @Test
    void stdinPayloadContainsRequiredFields(@TempDir Path dir) throws Exception {
        Path captured = dir.resolve("stdin.json");
        HookExecutor hooks = executor(configFor(HookEvent.PRE_TOOL_USE, null,
                "cat > " + captured, 5));
        hooks.runPreToolUse("Bash", MAPPER.createObjectNode().put("cmd", "ls"));

        String stdinJson = Files.readString(captured);
        JsonNode parsed = MAPPER.readTree(stdinJson);
        assertEquals("test-session", parsed.path("session_id").asText());
        assertEquals("/tmp", parsed.path("cwd").asText());
        assertEquals("PreToolUse", parsed.path("hook_event_name").asText());
        assertEquals("Bash", parsed.path("tool_name").asText());
        assertEquals("ls", parsed.path("tool_input").path("cmd").asText());
        assertTrue(parsed.has("transcript_path"));
    }

    @Test
    void postToolUsePayloadIncludesToolResponse(@TempDir Path dir) throws Exception {
        Path captured = dir.resolve("stdin.json");
        HookExecutor hooks = executor(configFor(HookEvent.POST_TOOL_USE, null,
                "cat > " + captured, 5));
        hooks.runPostToolUse("Bash", input(), "hello world", true);

        JsonNode parsed = MAPPER.readTree(Files.readString(captured));
        assertEquals("PostToolUse", parsed.path("hook_event_name").asText());
        assertEquals("hello world", parsed.path("tool_response").path("content").asText());
        assertTrue(parsed.path("tool_response").path("is_error").asBoolean());
    }

    @Test
    void emptyConfig_isEmpty() {
        HookExecutor hooks = new HookExecutor(null, "s", Path.of("/tmp"));
        assertTrue(hooks.isEmpty());
        assertInstanceOf(HookDecision.Allow.class, hooks.runPreToolUse("Bash", input()));
    }
}
