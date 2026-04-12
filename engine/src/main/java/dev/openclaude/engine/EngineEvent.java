package dev.openclaude.engine;

import dev.openclaude.core.model.StreamEvent;
import dev.openclaude.core.model.Usage;
import dev.openclaude.tools.ToolResult;

/**
 * Events emitted by the QueryEngine during execution.
 */
public sealed interface EngineEvent {

    /** Raw stream event from the LLM. */
    record Stream(StreamEvent event) implements EngineEvent {}

    /** A tool is about to be executed. */
    record ToolExecutionStart(String toolName, String toolUseId) implements EngineEvent {}

    /** A tool has finished executing. */
    record ToolExecutionEnd(String toolName, String toolUseId, ToolResult result) implements EngineEvent {}

    /** The agent loop completed successfully. */
    record Done(Usage totalUsage, int loopCount) implements EngineEvent {}

    /** An error occurred. */
    record Error(String message) implements EngineEvent {}
}
