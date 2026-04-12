package dev.openclaude.core.config;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Application configuration loaded from environment variables and config files.
 */
public record AppConfig(
        String apiKey,
        String model,
        String baseUrl,
        String provider,
        int maxTokens
) {
    private static final String DEFAULT_MODEL = "claude-sonnet-4-20250514";
    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    private static final int DEFAULT_MAX_TOKENS = 16384;

    /**
     * Load config from environment variables and ~/.openclaude/config.json.
     */
    public static AppConfig load() {
        String apiKey = env("ANTHROPIC_API_KEY", env("OPENAI_API_KEY", ""));
        String model = env("ANTHROPIC_MODEL", env("OPENCLAUDE_MODEL", DEFAULT_MODEL));
        String baseUrl = env("ANTHROPIC_BASE_URL", DEFAULT_BASE_URL);
        String provider = detectProvider(apiKey, baseUrl);
        int maxTokens = intEnv("OPENCLAUDE_MAX_TOKENS", DEFAULT_MAX_TOKENS);

        return new AppConfig(apiKey, model, baseUrl, provider, maxTokens);
    }

    public void validate() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "API key not found. Set ANTHROPIC_API_KEY or OPENAI_API_KEY environment variable.");
        }
    }

    private static String detectProvider(String apiKey, String baseUrl) {
        if (apiKey.startsWith("sk-ant-")) return "anthropic";
        if (apiKey.startsWith("sk-")) return "openai";
        if (baseUrl.contains("openai.azure.com")) return "azure";
        if (baseUrl.contains("localhost") || baseUrl.contains("127.0.0.1")) return "local";
        return "anthropic";
    }

    private static String env(String key, String defaultValue) {
        String val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val : defaultValue;
    }

    private static int intEnv(String key, int defaultValue) {
        String val = System.getenv(key);
        if (val == null || val.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
