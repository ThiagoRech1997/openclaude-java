package dev.openclaude.llm.provider;

import dev.openclaude.core.config.AppConfig;
import dev.openclaude.llm.LlmClient;
import dev.openclaude.llm.anthropic.AnthropicClient;
import dev.openclaude.llm.ollama.OllamaClient;
import dev.openclaude.llm.openai.OpenAIClient;

/**
 * Creates the appropriate LlmClient based on provider configuration.
 *
 * Provider routing:
 *   anthropic → AnthropicClient (Messages API)
 *   openai, azure, deepseek, groq, mistral, together, local, openrouter, github → OpenAIClient (Chat Completions)
 *   ollama → OllamaClient (/api/chat JSONL)
 */
public final class LlmClientFactory {

    private LlmClientFactory() {}

    public static LlmClient create(AppConfig config) {
        return switch (config.provider()) {
            case "anthropic" -> new AnthropicClient(config.apiKey(), config.baseUrl());
            case "ollama" -> new OllamaClient(config.baseUrl());
            case "openai", "azure", "deepseek", "groq", "mistral",
                 "together", "local", "openrouter", "github" ->
                    new OpenAIClient(config.apiKey(), config.baseUrl());
            default -> {
                // Try to guess: if base URL looks like Anthropic, use that; otherwise OpenAI-compatible
                if (config.baseUrl().contains("anthropic.com")) {
                    yield new AnthropicClient(config.apiKey(), config.baseUrl());
                }
                yield new OpenAIClient(config.apiKey(), config.baseUrl());
            }
        };
    }
}
