package dev.openclaude.engine.agents;

import dev.openclaude.tools.Tool;

import java.util.List;
import java.util.function.Predicate;

/**
 * Definition of a sub-agent type.
 *
 * <p>Exactly one of {@code toolFilter} and {@code toolWhitelist} is expected to be non-null:
 * built-ins use {@code toolFilter} (predicate-based) so they can see tools registered by plugins
 * or MCP after the runner is constructed; user-defined agents loaded from markdown use
 * {@code toolWhitelist} (name-based). When both are null the sub-agent inherits every tool
 * except {@code Agent} (no recursive sub-agent launches).
 */
public record SubAgentDefinition(
        String name,
        String description,
        String systemPrompt,
        List<String> toolWhitelist,
        Predicate<Tool> toolFilter,
        String modelOverride,
        String source
) {
    public static SubAgentDefinition builtIn(String name, String description, String systemPrompt,
                                             Predicate<Tool> toolFilter) {
        return new SubAgentDefinition(name, description, systemPrompt, null, toolFilter, null, "built-in");
    }
}
