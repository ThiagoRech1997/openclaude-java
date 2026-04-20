package dev.openclaude.core.hooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Runs the shell commands configured for a hook event and parses their stdout into a
 * {@link HookDecision}. The wire format — both the JSON written to stdin and the JSON
 * expected back on stdout — matches Claude Code's hook protocol.
 *
 * <h2>Asymmetry: PostToolUse cannot deny</h2>
 * The tool has already executed by the time a PostToolUse hook runs. A
 * {@code {"decision":"block"}} response from a PostToolUse hook is downgraded to
 * {@link HookDecision.Allow} (with the {@code reason} copied into
 * {@code additionalContext}), since the only lever left is to inject feedback to the
 * model. Only {@code continue:false} still escalates to {@link HookDecision.Stop}.
 */
public class HookExecutor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HookConfig config;
    private final String sessionId;
    private final Path cwd;

    public HookExecutor(HookConfig config, String sessionId, Path cwd) {
        this.config = config != null ? config : HookConfig.empty();
        this.sessionId = sessionId;
        this.cwd = cwd;
    }

    public boolean isEmpty() {
        return config.isEmpty();
    }

    public HookDecision runPreToolUse(String toolName, JsonNode toolInput) {
        ObjectNode payload = basePayload(HookEvent.PRE_TOOL_USE);
        payload.put("tool_name", toolName);
        payload.set("tool_input", toolInput != null ? toolInput : MAPPER.createObjectNode());
        return runChain(HookEvent.PRE_TOOL_USE, toolName, payload, true);
    }

    public HookDecision runPostToolUse(String toolName, JsonNode toolInput,
                                       String toolResponseText, boolean isError) {
        ObjectNode payload = basePayload(HookEvent.POST_TOOL_USE);
        payload.put("tool_name", toolName);
        payload.set("tool_input", toolInput != null ? toolInput : MAPPER.createObjectNode());
        ObjectNode response = MAPPER.createObjectNode();
        response.put("content", toolResponseText != null ? toolResponseText : "");
        response.put("is_error", isError);
        payload.set("tool_response", response);
        return runChain(HookEvent.POST_TOOL_USE, toolName, payload, false);
    }

    public HookDecision runUserPromptSubmit(String prompt) {
        ObjectNode payload = basePayload(HookEvent.USER_PROMPT_SUBMIT);
        payload.put("prompt", prompt != null ? prompt : "");
        return runChain(HookEvent.USER_PROMPT_SUBMIT, null, payload, true);
    }

    private ObjectNode basePayload(HookEvent event) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("session_id", sessionId != null ? sessionId : "");
        n.put("transcript_path", "");
        n.put("cwd", cwd != null ? cwd.toString() : "");
        n.put("hook_event_name", event.jsonName());
        return n;
    }

    /**
     * Dispatch every hook command for matching matchers. First Deny/Stop short-circuits;
     * ReplaceInput short-circuits as well (further hooks would have seen the old input).
     * Allow with additionalContext accumulates across multiple hooks.
     */
    private HookDecision runChain(HookEvent event, String toolName, ObjectNode payload, boolean allowDeny) {
        StringBuilder accumulatedContext = new StringBuilder();

        for (HookConfig.HookMatcher matcher : config.matchersFor(event)) {
            if (!matcher.matches(toolName)) continue;

            for (HookConfig.HookCommand cmd : matcher.hooks()) {
                HookDecision d = runOne(cmd, payload, allowDeny);
                if (d instanceof HookDecision.Stop) return d;
                if (d instanceof HookDecision.Deny) return d;
                if (d instanceof HookDecision.ReplaceInput) return d;
                if (d instanceof HookDecision.Allow a && a.additionalContext() != null) {
                    if (accumulatedContext.length() > 0) accumulatedContext.append("\n");
                    accumulatedContext.append(a.additionalContext());
                }
            }
        }

        return new HookDecision.Allow(
                accumulatedContext.length() > 0 ? accumulatedContext.toString() : null);
    }

    private HookDecision runOne(HookConfig.HookCommand cmd, ObjectNode payload, boolean allowDeny) {
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", cmd.command());
        if (cwd != null) pb.directory(cwd.toFile());

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            return allowDeny
                    ? new HookDecision.Deny("Hook command failed to start: " + e.getMessage())
                    : HookDecision.Allow.empty();
        }

        StreamDrainer outDrainer = new StreamDrainer(process.getInputStream());
        StreamDrainer errDrainer = new StreamDrainer(process.getErrorStream());
        Thread outT = new Thread(outDrainer, "hook-stdout");
        Thread errT = new Thread(errDrainer, "hook-stderr");
        outT.setDaemon(true);
        errT.setDaemon(true);
        outT.start();
        errT.start();

        try {
            try (OutputStream stdin = process.getOutputStream()) {
                MAPPER.writeValue(stdin, payload);
            } catch (IOException ignored) {
                // Hook closed stdin early — still collect its output.
            }

            boolean finished = process.waitFor(cmd.timeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                outT.join(1000);
                errT.join(1000);
                String reason = "Hook timed out after " + cmd.timeoutSeconds() + "s";
                return allowDeny ? new HookDecision.Deny(reason) : HookDecision.Allow.empty();
            }

            outT.join(1000);
            errT.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return allowDeny ? new HookDecision.Deny("Hook interrupted") : HookDecision.Allow.empty();
        }

        int exitCode = process.exitValue();
        String stdout = outDrainer.content();
        String stderr = errDrainer.content();

        return parseDecision(exitCode, stdout, stderr, allowDeny);
    }

    /**
     * Parse the hook's stdout/exit code into a decision. See class javadoc for semantics.
     * When {@code allowDeny} is false (PostToolUse), a {@code block}/{@code deny} decision
     * is downgraded to Allow(additionalContext=reason).
     */
    private HookDecision parseDecision(int exitCode, String stdout, String stderr, boolean allowDeny) {
        // Non-zero exit = deny; stderr becomes the reason.
        if (exitCode != 0) {
            String reason = !stderr.isBlank() ? stderr.trim()
                    : !stdout.isBlank() ? stdout.trim()
                    : "Hook exited with code " + exitCode;
            return allowDeny ? new HookDecision.Deny(reason)
                    : new HookDecision.Allow(reason);
        }

        String trimmed = stdout.trim();
        if (trimmed.isEmpty()) return HookDecision.Allow.empty();

        JsonNode root;
        try {
            root = MAPPER.readTree(trimmed);
        } catch (IOException e) {
            return HookDecision.Allow.empty();
        }
        if (root == null || !root.isObject()) return HookDecision.Allow.empty();

        JsonNode contNode = root.get("continue");
        if (contNode != null && contNode.isBoolean() && !contNode.asBoolean()) {
            String stopReason = root.path("stopReason").asText("Hook requested stop");
            return new HookDecision.Stop(stopReason);
        }

        JsonNode hso = root.get("hookSpecificOutput");
        if (hso != null && hso.isObject()) {
            String perm = hso.path("permissionDecision").asText(null);
            if ("deny".equalsIgnoreCase(perm)) {
                String reason = hso.path("permissionDecisionReason").asText("denied by hook");
                return allowDeny ? new HookDecision.Deny(reason) : new HookDecision.Allow(reason);
            }
            String addCtx = hso.path("additionalContext").asText(null);
            if (addCtx != null && !addCtx.isEmpty()) return new HookDecision.Allow(addCtx);
        }

        JsonNode decision = root.get("decision");
        if (decision != null && decision.isTextual()) {
            String d = decision.asText();
            if ("block".equalsIgnoreCase(d) || "deny".equalsIgnoreCase(d)) {
                String reason = root.path("reason").asText("denied by hook");
                return allowDeny ? new HookDecision.Deny(reason) : new HookDecision.Allow(reason);
            }
            if ("approve".equalsIgnoreCase(d)) {
                String addCtx = root.path("reason").asText(null);
                return new HookDecision.Allow(
                        addCtx == null || addCtx.isEmpty() ? null : addCtx);
            }
        }

        // Pre-tool only: input replacement.
        if (allowDeny) {
            JsonNode newInput = root.get("tool_input");
            if (newInput != null && newInput.isObject()) {
                return new HookDecision.ReplaceInput(newInput);
            }
        }

        JsonNode addCtx = root.get("additionalContext");
        if (addCtx != null && addCtx.isTextual() && !addCtx.asText().isEmpty()) {
            return new HookDecision.Allow(addCtx.asText());
        }

        return HookDecision.Allow.empty();
    }

    private static final class StreamDrainer implements Runnable {
        private final InputStream stream;
        private final StringBuilder buf = new StringBuilder();

        StreamDrainer(InputStream stream) { this.stream = stream; }

        @Override
        public void run() {
            byte[] chunk = new byte[4096];
            try {
                int n;
                while ((n = stream.read(chunk)) >= 0) {
                    buf.append(new String(chunk, 0, n, StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
                // Stream closed; whatever we collected stands.
            }
        }

        synchronized String content() { return buf.toString(); }
    }
}
