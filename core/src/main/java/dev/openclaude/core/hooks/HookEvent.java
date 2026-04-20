package dev.openclaude.core.hooks;

/**
 * Hook event names — mirror the Claude Code hook lifecycle.
 *
 * <p>Only {@link #PRE_TOOL_USE}, {@link #POST_TOOL_USE} and {@link #USER_PROMPT_SUBMIT}
 * are wired to dispatch sites today; the remaining constants are declared so that
 * follow-up tickets only need to add invocation points without touching the enum.
 */
public enum HookEvent {
    PRE_TOOL_USE("PreToolUse"),
    POST_TOOL_USE("PostToolUse"),
    USER_PROMPT_SUBMIT("UserPromptSubmit"),
    SESSION_START("SessionStart"),
    SESSION_END("SessionEnd"),
    STOP("Stop"),
    SUBAGENT_STOP("SubagentStop"),
    NOTIFICATION("Notification");

    private final String jsonName;

    HookEvent(String jsonName) {
        this.jsonName = jsonName;
    }

    public String jsonName() {
        return jsonName;
    }

    public static HookEvent fromJsonName(String name) {
        for (HookEvent e : values()) {
            if (e.jsonName.equals(name)) return e;
        }
        return null;
    }
}
