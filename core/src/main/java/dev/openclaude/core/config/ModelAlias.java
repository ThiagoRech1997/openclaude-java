package dev.openclaude.core.config;

import java.util.Map;

/**
 * Maps short model aliases (sonnet, opus, haiku) to full model identifiers.
 */
public final class ModelAlias {

    private static final Map<String, String> ALIASES = Map.of(
            "sonnet", "claude-sonnet-4-20250514",
            "opus", "claude-opus-4-20250514",
            "haiku", "claude-haiku-3-5-20241022"
    );

    private ModelAlias() {}

    /**
     * Resolve a model alias to a full model ID.
     *
     * @param alias    the alias ("sonnet", "opus", "haiku") or null
     * @param fallback the model to use if alias is null or blank
     * @return the resolved model ID
     */
    public static String resolve(String alias, String fallback) {
        if (alias == null || alias.isBlank()) return fallback;
        return ALIASES.getOrDefault(alias, alias);
    }
}
