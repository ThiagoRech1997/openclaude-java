package dev.openclaude.engine;

import dev.openclaude.core.model.*;
import dev.openclaude.llm.LlmClient;
import dev.openclaude.llm.LlmRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * Compacts a conversation by summarizing older messages to save context window.
 * Sends the conversation to the LLM with a summarization prompt, then replaces
 * the old messages with the summary.
 */
public class ContextCompactor {

    private final LlmClient client;
    private final String model;
    private final int maxTokens;

    public ContextCompactor(LlmClient client, String model, int maxTokens) {
        this.client = client;
        this.model = model;
        this.maxTokens = maxTokens;
    }

    /**
     * Compact a list of messages by summarizing all but the last N messages.
     *
     * @param messages the full conversation
     * @param keepLastN number of recent messages to keep verbatim
     * @return compacted message list
     */
    public List<Message> compact(List<Message> messages, int keepLastN) {
        if (messages.size() <= keepLastN + 2) {
            return messages; // Nothing to compact
        }

        int splitPoint = messages.size() - keepLastN;
        List<Message> toSummarize = messages.subList(0, splitPoint);
        List<Message> toKeep = messages.subList(splitPoint, messages.size());

        // Build summary prompt
        StringBuilder summaryInput = new StringBuilder();
        summaryInput.append("Summarize the following conversation context concisely. ");
        summaryInput.append("Include key decisions, files modified, tool results, and important findings. ");
        summaryInput.append("Keep it under 500 words.\n\n");

        for (Message msg : toSummarize) {
            String role = msg.role().value();
            StringBuilder text = new StringBuilder();
            for (ContentBlock block : msg.content()) {
                if (block instanceof ContentBlock.Text t) {
                    text.append(t.text());
                } else if (block instanceof ContentBlock.ToolUse tu) {
                    text.append("[Tool: ").append(tu.name()).append("]");
                } else if (block instanceof ContentBlock.ToolResult tr) {
                    String trText = tr.textContent();
                    String preview = trText.length() > 200
                            ? trText.substring(0, 200) + "..."
                            : trText;
                    text.append("[Result: ").append(preview).append("]");
                }
            }
            summaryInput.append(role).append(": ").append(text).append("\n\n");
        }

        // Get summary from LLM
        String summary = getSummary(summaryInput.toString());

        // Build compacted message list
        List<Message> compacted = new ArrayList<>();
        compacted.add(new Message.UserMessage(
                "[Context summary of " + toSummarize.size() + " earlier messages]\n\n" + summary));
        compacted.add(new Message.AssistantMessage(
                List.of(new ContentBlock.Text("Understood. I have the context from our earlier conversation.")),
                "end_turn", Usage.ZERO));
        compacted.addAll(toKeep);

        return compacted;
    }

    private String getSummary(String input) {
        List<Message> msgs = List.of(new Message.UserMessage(input));
        LlmRequest request = new LlmRequest(model, "You are a conversation summarizer.", msgs, maxTokens / 4);

        StringBuilder result = new StringBuilder();
        client.streamMessage(request, event -> {
            if (event instanceof StreamEvent.TextDelta td) {
                result.append(td.text());
            }
        });

        return result.length() > 0 ? result.toString() : "(Summary unavailable)";
    }
}
