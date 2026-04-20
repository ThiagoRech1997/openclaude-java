package dev.openclaude.core.hooks;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Outcome of running a hook (or a chain of hooks) for a single event.
 *
 * <p>Semantics:
 * <ul>
 *   <li>{@link Allow} — proceed with the action. Optional {@code additionalContext}
 *       is injected into the next model turn (UserPromptSubmit / PostToolUse).</li>
 *   <li>{@link Deny} — abort the specific action (refuse a tool call, reject a prompt).
 *       Not emitted by PostToolUse — that hook can't retroactively un-run a tool.</li>
 *   <li>{@link Stop} — abort the whole session.</li>
 *   <li>{@link ReplaceInput} — PreToolUse only: rewrite {@code tool_input} before the tool runs.</li>
 * </ul>
 */
public sealed interface HookDecision {

    record Allow(String additionalContext) implements HookDecision {
        public static Allow empty() { return new Allow(null); }
    }

    record Deny(String reason) implements HookDecision {}

    record Stop(String stopReason) implements HookDecision {}

    record ReplaceInput(JsonNode newInput) implements HookDecision {}
}
