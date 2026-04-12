package dev.openclaude.llm.provider;

import dev.openclaude.core.config.AppConfig;
import dev.openclaude.llm.LlmClient;
import dev.openclaude.llm.anthropic.AnthropicClient;

/**
 * Creates the appropriate LlmClient based on provider configuration.
 */
public final class LlmClientFactory {

    private LlmClientFactory() {}

    public static LlmClient create(AppConfig config) {
        return switch (config.provider()) {
            case "anthropic" -> new AnthropicClient(config.apiKey(), config.baseUrl());
            // Future providers:
            // case "openai" -> new OpenAIClient(config.apiKey(), config.baseUrl());
            // case "ollama" -> new OllamaClient(config.baseUrl());
            default -> new AnthropicClient(config.apiKey(), config.baseUrl());
        };
    }
}
