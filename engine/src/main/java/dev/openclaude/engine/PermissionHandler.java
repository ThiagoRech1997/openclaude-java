package dev.openclaude.engine;

import com.fasterxml.jackson.databind.JsonNode;
import dev.openclaude.core.permissions.PermissionManager;

/**
 * Resolves an {@link PermissionManager.PermissionDecision#ASK} decision from the engine.
 *
 * <p>The engine calls this handler synchronously on its own thread when the
 * {@link PermissionManager} cannot decide by itself whether a tool should run.
 * Implementations may block (e.g. to prompt the user) and are responsible for
 * any side effects on {@link PermissionManager} (adding always-allow rules,
 * switching mode). The engine only observes the returned decision — which must
 * be {@link PermissionManager.PermissionDecision#ALLOWED} or
 * {@link PermissionManager.PermissionDecision#DENIED}.
 */
@FunctionalInterface
public interface PermissionHandler {

    PermissionManager.PermissionDecision ask(String toolName, JsonNode input, boolean isReadOnly);
}
