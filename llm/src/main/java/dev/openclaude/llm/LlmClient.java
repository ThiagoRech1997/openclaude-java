package dev.openclaude.llm;

import dev.openclaude.core.model.Message;
import dev.openclaude.core.model.StreamEvent;

import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Unified interface for all LLM providers.
 * Implementations handle provider-specific protocol details.
 */
public interface LlmClient {

    /**
     * Send messages and stream back events.
     *
     * @param request the request parameters
     * @param eventHandler callback invoked for each stream event
     */
    void streamMessage(LlmRequest request, Consumer<StreamEvent> eventHandler);

    /**
     * Returns the provider name (e.g., "anthropic", "openai", "ollama").
     */
    String providerName();
}
