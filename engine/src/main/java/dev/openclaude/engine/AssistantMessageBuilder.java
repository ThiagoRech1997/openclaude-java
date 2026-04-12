package dev.openclaude.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openclaude.core.model.ContentBlock;
import dev.openclaude.core.model.Message;
import dev.openclaude.core.model.Usage;

import java.util.ArrayList;
import java.util.List;

/**
 * Accumulates stream events into a complete AssistantMessage.
 */
class AssistantMessageBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<ContentBlock> contentBlocks = new ArrayList<>();
    private final StringBuilder currentText = new StringBuilder();
    private final StringBuilder currentThinking = new StringBuilder();
    private String currentToolId;
    private String currentToolName;
    private final StringBuilder currentToolJson = new StringBuilder();
    private String stopReason;
    private Usage usage = Usage.ZERO;
    private String error;

    void addText(String text) {
        currentText.append(text);
    }

    void addThinking(String thinking) {
        currentThinking.append(thinking);
    }

    void startToolUse(String id, String name) {
        // Flush any pending text
        flushText();
        flushThinking();
        currentToolId = id;
        currentToolName = name;
        currentToolJson.setLength(0);
    }

    void appendToolInput(String partialJson) {
        currentToolJson.append(partialJson);
    }

    void finishContentBlock() {
        if (currentToolId != null) {
            try {
                JsonNode input = currentToolJson.length() > 0
                        ? MAPPER.readTree(currentToolJson.toString())
                        : MAPPER.createObjectNode();
                contentBlocks.add(new ContentBlock.ToolUse(currentToolId, currentToolName, input));
            } catch (Exception e) {
                contentBlocks.add(new ContentBlock.ToolUse(
                        currentToolId, currentToolName, MAPPER.createObjectNode()));
            }
            currentToolId = null;
            currentToolName = null;
            currentToolJson.setLength(0);
        } else {
            flushText();
            flushThinking();
        }
    }

    void setStopReason(String reason) {
        this.stopReason = reason;
    }

    void setUsage(Usage usage) {
        if (usage != null) {
            this.usage = usage;
        }
    }

    void setError(String error) {
        this.error = error;
    }

    boolean hasError() {
        return error != null;
    }

    String getError() {
        return error;
    }

    Message.AssistantMessage build() {
        flushText();
        flushThinking();

        if (contentBlocks.isEmpty()) {
            contentBlocks.add(new ContentBlock.Text(""));
        }

        return new Message.AssistantMessage(
                List.copyOf(contentBlocks),
                stopReason,
                usage
        );
    }

    private void flushText() {
        if (currentText.length() > 0) {
            contentBlocks.add(new ContentBlock.Text(currentText.toString()));
            currentText.setLength(0);
        }
    }

    private void flushThinking() {
        if (currentThinking.length() > 0) {
            contentBlocks.add(new ContentBlock.Thinking(currentThinking.toString()));
            currentThinking.setLength(0);
        }
    }
}
