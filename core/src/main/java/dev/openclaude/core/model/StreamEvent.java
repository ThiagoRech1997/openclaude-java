package dev.openclaude.core.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Events emitted during streaming of an LLM response.
 */
public sealed interface StreamEvent {

    /** Start of a new message. */
    record MessageStart(String messageId, String model, Usage usage) implements StreamEvent {}

    /** Incremental text delta. */
    record TextDelta(String text) implements StreamEvent {}

    /** Incremental thinking/reasoning delta. */
    record ThinkingDelta(String thinking) implements StreamEvent {}

    /** Start of a tool use block. */
    record ToolUseStart(String id, String name) implements StreamEvent {}

    /** Incremental JSON delta for tool input. */
    record ToolInputDelta(String partialJson) implements StreamEvent {}

    /** A content block has finished. */
    record ContentBlockStop(int index) implements StreamEvent {}

    /** The message is complete. */
    record MessageComplete(Message.AssistantMessage message) implements StreamEvent {}

    /** The stream ended with an error. */
    record Error(String message, Exception cause) implements StreamEvent {}

    /** Message delta with stop reason and usage. */
    record MessageDelta(String stopReason, Usage usage) implements StreamEvent {}
}
