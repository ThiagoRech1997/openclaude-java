package dev.openclaude.core.permissions;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages tool permissions: which tools can run, denial tracking,
 * and always-allow/always-deny rules.
 */
public class PermissionManager {

    private volatile PermissionMode mode;
    private final Set<String> alwaysAllow = ConcurrentHashMap.newKeySet();
    private final Set<String> alwaysDeny = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> denialCounts = new ConcurrentHashMap<>();

    public PermissionManager() {
        this(PermissionMode.DEFAULT);
    }

    public PermissionManager(PermissionMode mode) {
        this.mode = mode;
    }

    public PermissionMode getMode() {
        return mode;
    }

    public void setMode(PermissionMode mode) {
        this.mode = mode;
    }

    /**
     * Check if a tool should be allowed to run.
     *
     * @param toolName the tool name
     * @param isReadOnly whether the tool is read-only
     * @return the permission decision
     */
    public PermissionDecision check(String toolName, boolean isReadOnly) {
        // Always-deny rules take priority
        if (alwaysDeny.contains(toolName)) {
            return PermissionDecision.DENIED;
        }

        // Always-allow rules
        if (alwaysAllow.contains(toolName)) {
            return PermissionDecision.ALLOWED;
        }

        return switch (mode) {
            case AUTO_APPROVE -> PermissionDecision.ALLOWED;
            case AUTO_DENY -> isReadOnly ? PermissionDecision.ALLOWED : PermissionDecision.DENIED;
            case PLAN -> isReadOnly ? PermissionDecision.ALLOWED : PermissionDecision.DENIED;
            case DEFAULT -> isReadOnly ? PermissionDecision.ALLOWED : PermissionDecision.ASK;
        };
    }

    public void addAlwaysAllow(String toolName) {
        alwaysAllow.add(toolName);
        alwaysDeny.remove(toolName);
    }

    public void addAlwaysDeny(String toolName) {
        alwaysDeny.add(toolName);
        alwaysAllow.remove(toolName);
    }

    public void recordDenial(String toolName) {
        denialCounts.merge(toolName, 1, Integer::sum);
    }

    public int getDenialCount(String toolName) {
        return denialCounts.getOrDefault(toolName, 0);
    }

    public Map<String, Integer> getAllDenials() {
        return Collections.unmodifiableMap(denialCounts);
    }

    public Set<String> getAllowedTools() {
        return Collections.unmodifiableSet(alwaysAllow);
    }

    public Set<String> getDeniedTools() {
        return Collections.unmodifiableSet(alwaysDeny);
    }

    public enum PermissionDecision {
        ALLOWED,
        DENIED,
        ASK
    }
}
