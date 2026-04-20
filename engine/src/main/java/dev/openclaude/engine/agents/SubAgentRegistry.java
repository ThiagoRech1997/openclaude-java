package dev.openclaude.engine.agents;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Registry of sub-agent definitions, keyed by name.
 *
 * <p>Built-ins ({@code general-purpose}, {@code Explore}, {@code Plan}) are registered in the
 * constructor. Custom agents loaded from markdown can overwrite a built-in by reusing the same
 * name — the last {@link #register} call wins.
 */
public final class SubAgentRegistry {

    static final String GENERAL_PURPOSE_PROMPT =
            "You are a sub-agent launched to handle a specific task. "
                    + "Complete the task thoroughly and return a concise summary of what you did and found. "
                    + "You have access to the same tools as the parent agent.";

    static final String EXPLORE_PROMPT =
            "You are an exploration sub-agent optimized for searching and reading code. "
                    + "You have read-only access — do NOT attempt to create, modify, or delete any files. "
                    + "Search, read, and analyze the codebase, then return a concise summary of your findings.";

    static final String PLAN_PROMPT =
            "You are a planning sub-agent. Your job is to explore the codebase and design implementation plans. "
                    + "You must NOT create, modify, or delete files. "
                    + "Analyze the codebase and return a detailed, actionable implementation plan.";

    private final LinkedHashMap<String, SubAgentDefinition> byName = new LinkedHashMap<>();

    public SubAgentRegistry() {
        register(SubAgentDefinition.builtIn(
                "general-purpose", "General-purpose sub-agent.", GENERAL_PURPOSE_PROMPT,
                t -> !t.name().equals("Agent")));
        register(SubAgentDefinition.builtIn(
                "Explore", "Read-only exploration sub-agent.", EXPLORE_PROMPT,
                t -> (t.isReadOnly() || t.name().equals("Bash")) && !t.name().equals("Agent")));
        register(SubAgentDefinition.builtIn(
                "Plan", "Planning sub-agent (no file edits).", PLAN_PROMPT,
                t -> !t.name().equals("FileWrite") && !t.name().equals("FileEdit")
                        && !t.name().equals("Agent")));
    }

    public void register(SubAgentDefinition def) {
        byName.put(def.name(), def);
    }

    public SubAgentDefinition get(String name) {
        if (name == null) return byName.get("general-purpose");
        return byName.get(name);
    }

    public SubAgentDefinition fallback() {
        return byName.get("general-purpose");
    }

    public List<String> names() {
        return List.copyOf(byName.keySet());
    }

    public Collection<SubAgentDefinition> all() {
        return Collections.unmodifiableCollection(byName.values());
    }
}
