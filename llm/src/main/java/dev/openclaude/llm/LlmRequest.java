package dev.openclaude.llm;

import dev.openclaude.core.model.Message;

import java.util.List;

/**
 * Parameters for an LLM request.
 */
public record LlmRequest(
        String model,
        String systemPrompt,
        List<Message> messages,
        int maxTokens,
        double temperature
) {
    public LlmRequest(String model, String systemPrompt, List<Message> messages, int maxTokens) {
        this(model, systemPrompt, messages, maxTokens, 1.0);
    }
}
