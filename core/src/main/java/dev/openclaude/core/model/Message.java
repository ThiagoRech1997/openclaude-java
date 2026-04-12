package dev.openclaude.core.model;

import java.util.List;

/**
 * Messages exchanged in the conversation, following Anthropic Messages API structure.
 */
public sealed interface Message {

    Role role();
    List<ContentBlock> content();

    record UserMessage(
            List<ContentBlock> content
    ) implements Message {
        public UserMessage(String text) {
            this(List.of(new ContentBlock.Text(text)));
        }

        @Override
        public Role role() {
            return Role.USER;
        }
    }

    record AssistantMessage(
            List<ContentBlock> content,
            String stopReason,
            Usage usage
    ) implements Message {
        @Override
        public Role role() {
            return Role.ASSISTANT;
        }
    }
}
