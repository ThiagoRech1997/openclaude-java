package dev.openclaude.mcp;

/**
 * Exception thrown by MCP operations.
 */
public class McpException extends Exception {

    private final int code;

    public McpException(String message) {
        this(message, -1);
    }

    public McpException(String message, int code) {
        super(message);
        this.code = code;
    }

    public McpException(String message, Throwable cause) {
        super(message, cause);
        this.code = -1;
    }

    public int getCode() {
        return code;
    }
}
