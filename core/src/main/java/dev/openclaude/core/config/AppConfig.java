package dev.openclaude.core.config;

/**
 * Application configuration loaded from environment variables.
 *
 * Supported providers:
 *   - anthropic: ANTHROPIC_API_KEY (default)
 *   - openai:    OPENAI_API_KEY + optional OPENAI_BASE_URL
 *   - ollama:    OLLAMA_BASE_URL (no API key needed)
 *   - openrouter: OPENROUTER_API_KEY
 *   - github:    GITHUB_TOKEN + CLAUDE_CODE_USE_GITHUB=1
 */
public record AppConfig(
        String apiKey,
        String model,
        String baseUrl,
        String provider,
        int maxTokens
) {
    private static final String DEFAULT_MODEL_ANTHROPIC = "claude-sonnet-4-20250514";
    private static final String DEFAULT_MODEL_OPENAI = "gpt-4o";
    private static final String DEFAULT_MODEL_OLLAMA = "llama3.1";
    private static final String DEFAULT_BASE_URL_ANTHROPIC = "https://api.anthropic.com";
    private static final String DEFAULT_BASE_URL_OPENAI = "https://api.openai.com/v1";
    private static final String DEFAULT_BASE_URL_OLLAMA = "http://localhost:11434";
    private static final String DEFAULT_BASE_URL_OPENROUTER = "https://openrouter.ai/api/v1";
    private static final int DEFAULT_MAX_TOKENS = 16384;

    /**
     * Load config from environment variables.
     * Detection order: explicit provider env → API key prefix → base URL heuristics.
     */
    public static AppConfig load() {
        // Check for explicit Ollama
        String ollamaUrl = env("OLLAMA_BASE_URL", env("OLLAMA_HOST", ""));
        if (!ollamaUrl.isBlank()) {
            String model = env("OLLAMA_MODEL", env("OPENCLAUDE_MODEL", DEFAULT_MODEL_OLLAMA));
            return new AppConfig("", model, ollamaUrl, "ollama",
                    intEnv("OPENCLAUDE_MAX_TOKENS", DEFAULT_MAX_TOKENS));
        }

        // Check for OpenRouter
        String openrouterKey = env("OPENROUTER_API_KEY", "");
        if (!openrouterKey.isBlank()) {
            String model = env("OPENCLAUDE_MODEL", "anthropic/claude-sonnet-4-20250514");
            String baseUrl = env("OPENROUTER_BASE_URL", DEFAULT_BASE_URL_OPENROUTER);
            return new AppConfig(openrouterKey, model, baseUrl, "openrouter",
                    intEnv("OPENCLAUDE_MAX_TOKENS", DEFAULT_MAX_TOKENS));
        }

        // Check for GitHub Models
        String githubToken = env("GITHUB_TOKEN", env("GH_TOKEN", ""));
        boolean useGithub = isTrue("CLAUDE_CODE_USE_GITHUB");
        if (useGithub && !githubToken.isBlank()) {
            String model = env("OPENCLAUDE_MODEL", "gpt-4o");
            String baseUrl = env("GITHUB_MODELS_BASE_URL", "https://models.inference.ai.azure.com/v1");
            return new AppConfig(githubToken, model, baseUrl, "github",
                    intEnv("OPENCLAUDE_MAX_TOKENS", DEFAULT_MAX_TOKENS));
        }

        // Check for OpenAI (explicit or by key prefix)
        String openaiKey = env("OPENAI_API_KEY", "");
        boolean useOpenai = isTrue("CLAUDE_CODE_USE_OPENAI");
        if (useOpenai || (!openaiKey.isBlank() && env("ANTHROPIC_API_KEY", "").isBlank())) {
            String model = env("OPENAI_MODEL", env("OPENCLAUDE_MODEL", DEFAULT_MODEL_OPENAI));
            String baseUrl = env("OPENAI_BASE_URL", DEFAULT_BASE_URL_OPENAI);
            String provider = detectOpenAIProvider(baseUrl);
            return new AppConfig(openaiKey, model, baseUrl, provider,
                    intEnv("OPENCLAUDE_MAX_TOKENS", DEFAULT_MAX_TOKENS));
        }

        // Default: Anthropic
        String apiKey = env("ANTHROPIC_API_KEY", "");
        String model = env("ANTHROPIC_MODEL", env("OPENCLAUDE_MODEL", DEFAULT_MODEL_ANTHROPIC));
        String baseUrl = env("ANTHROPIC_BASE_URL", DEFAULT_BASE_URL_ANTHROPIC);
        int maxTokens = intEnv("OPENCLAUDE_MAX_TOKENS", DEFAULT_MAX_TOKENS);

        return new AppConfig(apiKey, model, baseUrl, "anthropic", maxTokens);
    }

    public void validate() {
        if ("ollama".equals(provider)) {
            return; // Ollama doesn't need an API key
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "API key not found. Set one of: ANTHROPIC_API_KEY, OPENAI_API_KEY, "
                    + "OPENROUTER_API_KEY, OLLAMA_BASE_URL, or GITHUB_TOKEN.");
        }
    }

    /**
     * Detect specific OpenAI-compatible provider from base URL.
     */
    private static String detectOpenAIProvider(String baseUrl) {
        if (baseUrl.contains("openai.azure.com")) return "azure";
        if (baseUrl.contains("openrouter.ai")) return "openrouter";
        if (baseUrl.contains("deepseek.com")) return "deepseek";
        if (baseUrl.contains("groq.com")) return "groq";
        if (baseUrl.contains("mistral.ai")) return "mistral";
        if (baseUrl.contains("together.xyz")) return "together";
        if (baseUrl.contains("localhost") || baseUrl.contains("127.0.0.1")) {
            // Could be LM Studio or other local
            return "local";
        }
        return "openai";
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

    private static boolean isTrue(String envKey) {
        String val = System.getenv(envKey);
        return "1".equals(val) || "true".equalsIgnoreCase(val);
    }
}
