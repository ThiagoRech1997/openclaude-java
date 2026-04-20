package dev.openclaude.core.hooks;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Parsed hooks configuration. Mirrors the Claude Code {@code settings.json → hooks} schema:
 *
 * <pre>
 * {
 *   "hooks": {
 *     "PreToolUse": [
 *       {
 *         "matcher": "Bash|Edit",
 *         "hooks": [ { "type": "command", "command": "...", "timeout": 30 } ]
 *       }
 *     ]
 *   }
 * }
 * </pre>
 */
public record HookConfig(Map<HookEvent, List<HookMatcher>> byEvent) {

    public static final int DEFAULT_TIMEOUT_SECONDS = 60;

    public static HookConfig empty() {
        return new HookConfig(Map.of());
    }

    public boolean isEmpty() {
        return byEvent.isEmpty();
    }

    public List<HookMatcher> matchersFor(HookEvent event) {
        return byEvent.getOrDefault(event, List.of());
    }

    /**
     * A single matcher entry for an event — pairs a regex (tested against the tool name
     * for PreToolUse/PostToolUse; ignored for events without a tool) with the list of
     * commands to run when it matches.
     *
     * <p>A {@code null} pattern means match-all.
     */
    public record HookMatcher(Pattern matcher, List<HookCommand> hooks) {
        public boolean matches(String toolName) {
            if (matcher == null) return true;
            if (toolName == null) return true;
            return matcher.matcher(toolName).find();
        }
    }

    /**
     * One shell command to run for a hook entry.
     * {@code type} is preserved from the JSON for forward compatibility — today only
     * {@code "command"} is supported.
     */
    public record HookCommand(String type, String command, int timeoutSeconds) {}
}
