package dev.openclaude.llm;

import com.fasterxml.jackson.databind.node.ArrayNode;
import dev.openclaude.core.model.Message;

import java.util.List;

/**
 * Parameters for an LLM request.
 *
 * <p>{@code temperature} is nullable: null means "don't send the parameter, use
 * the provider default" (also required by models that reject it, e.g. o-series).
 */
public record LlmRequest(
        String model,
        String systemPrompt,
        List<Message> messages,
        int maxTokens,
        Double temperature,
        ArrayNode tools
) {
    public LlmRequest(String model, String systemPrompt, List<Message> messages, int maxTokens) {
        this(model, systemPrompt, messages, maxTokens, null, null);
    }

    public LlmRequest withTools(ArrayNode tools) {
        return new LlmRequest(model, systemPrompt, messages, maxTokens, temperature, tools);
    }

    public LlmRequest withTemperature(double temperature) {
        return new LlmRequest(model, systemPrompt, messages, maxTokens, temperature, tools);
    }
}
