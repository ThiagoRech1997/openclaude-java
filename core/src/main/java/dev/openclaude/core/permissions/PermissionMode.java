package dev.openclaude.core.permissions;

/**
 * Permission modes controlling how tool execution is authorized.
 */
public enum PermissionMode {
    /** Default: ask for dangerous operations, auto-allow read-only. */
    DEFAULT,
    /** Plan mode: read-only tools only, no mutations. */
    PLAN,
    /** Auto-approve all tool calls. */
    AUTO_APPROVE,
    /** Auto-deny all tool calls that need permission. */
    AUTO_DENY
}
