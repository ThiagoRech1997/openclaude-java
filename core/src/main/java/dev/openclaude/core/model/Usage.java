package dev.openclaude.core.model;

/**
 * Token usage information from the API response.
 */
public record Usage(
        int inputTokens,
        int outputTokens,
        int cacheCreationInputTokens,
        int cacheReadInputTokens
) {
    public static final Usage ZERO = new Usage(0, 0, 0, 0);

    public Usage add(Usage other) {
        return new Usage(
                inputTokens + other.inputTokens,
                outputTokens + other.outputTokens,
                cacheCreationInputTokens + other.cacheCreationInputTokens,
                cacheReadInputTokens + other.cacheReadInputTokens
        );
    }

    public int totalTokens() {
        return inputTokens + outputTokens;
    }
}
