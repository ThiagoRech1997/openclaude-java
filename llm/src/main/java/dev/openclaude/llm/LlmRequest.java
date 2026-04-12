package dev.openclaude.llm;

import com.fasterxml.jackson.databind.node.ArrayNode;
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
        double temperature,
        ArrayNode tools
) {
    public LlmRequest(String model, String systemPrompt, List<Message> messages, int maxTokens) {
        this(model, systemPrompt, messages, maxTokens, 1.0, null);
    }

    public LlmRequest withTools(ArrayNode tools) {
        return new LlmRequest(model, systemPrompt, messages, maxTokens, temperature, tools);
    }
}
