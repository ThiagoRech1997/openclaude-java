# Tool System

The tool system is the agent's interface to the outside world. Tools let the LLM read files, write code, run commands, search codebases, and more.

## Tool Interface

Every tool implements the `Tool` interface (`tools/src/main/java/dev/openclaude/tools/Tool.java`):

```java
public interface Tool {
    String name();                              // Unique name sent to LLM
    String description();                       // Human-readable description
    JsonNode inputSchema();                     // JSON Schema for parameters
    default boolean isReadOnly() { return false; }
    default boolean isEnabled() { return true; }
    ToolResult execute(JsonNode input, ToolUseContext context);
}
```

### ToolResult

```java
public record ToolResult(String content, boolean isError) {
    public static ToolResult success(String content);
    public static ToolResult error(String message);
}
```

### ToolUseContext

```java
public record ToolUseContext(Path workingDirectory) {}
```

## ToolRegistry

The `ToolRegistry` is the central collection of all available tools:

```java
public final class ToolRegistry {
    void register(Tool tool);
    Optional<Tool> findByName(String name);
    List<Tool> enabledTools();
    ArrayNode toApiToolsArray();  // Generates LLM tool definitions
    int size();
}
```

Tools are registered in `Main.createToolRegistry()` in this order:
1. Built-in tools (Bash, Read, Write, Edit, Glob, Grep)
2. AgentTool (sub-agents)
3. Plugin tools (from `PluginLoader`)
4. MCP tools (from `McpToolBridge`)

## Built-in Tools

### Bash

**Name:** `Bash` | **Read-only:** No

Executes shell commands via `ProcessBuilder`.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `command` | string | yes | Shell command to execute |
| `timeout` | integer | no | Timeout in milliseconds (max 600000) |

**Behavior:**
- Runs in the working directory
- Captures stdout and stderr
- Enforces timeout (default 120s, max 10min)
- Truncates output beyond 512KB
- Returns exit code in error messages for non-zero exits

### Read

**Name:** `Read` | **Read-only:** Yes

Reads file contents with line numbers.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file_path` | string | yes | Absolute or relative path |
| `offset` | integer | no | Starting line (0-based) |
| `limit` | integer | no | Number of lines to read (default 2000) |

**Output format:** `1\tfirst line\n2\tsecond line\n...` (1-based line numbers with tab separator)

### Write

**Name:** `Write` | **Read-only:** No

Writes content to a file, creating parent directories if needed.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file_path` | string | yes | Absolute or relative path |
| `content` | string | yes | File content to write |

### Edit

**Name:** `Edit` | **Read-only:** No

Performs search-and-replace edits within a file.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file_path` | string | yes | Absolute or relative path |
| `edits` | array | yes | Array of `{old_str, new_str}` replacements |

**Behavior:** Each edit finds `old_str` in the file and replaces it with `new_str`. Validates that each `old_str` exists.

### Glob

**Name:** `Glob` | **Read-only:** Yes

Finds files matching a glob pattern.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `pattern` | string | yes | Glob pattern (e.g., `**/*.java`) |
| `path` | string | no | Directory to search in |

**Behavior:**
- Uses `FileSystems.getPathMatcher`
- Skips `.git`, `node_modules`, `build`, `.gradle`, `target`
- Limits to 500 results
- Sorted by modification time (most recent first)

### Grep

**Name:** `Grep` | **Read-only:** Yes

Searches file contents using regex patterns.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `pattern` | string | yes | Regex pattern |
| `path` | string | no | Directory to search in |
| `glob` | string | no | File pattern filter |
| `output_mode` | string | no | `content`, `files_with_matches`, or `count` |
| `-i` | boolean | no | Case-insensitive search |
| `head_limit` | integer | no | Limit results (default 250) |

**Behavior:** Prefers `rg` (ripgrep) if available, falls back to system `grep`.

### Agent

**Name:** `Agent` | **Read-only:** No

Launches a sub-agent to handle complex tasks.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `prompt` | string | yes | Task for the sub-agent |
| `description` | string | no | Short task description |

**Behavior:** Creates an isolated `QueryEngine` via `SubAgentRunner`. The sub-agent has access to the same tools and LLM but runs in a separate conversation thread.

## SchemaBuilder

Fluent builder for creating JSON Schema definitions:

```java
JsonNode schema = SchemaBuilder.object()
    .stringProp("command", "Shell command to execute", true)
    .intProp("timeout", "Timeout in milliseconds", false)
    .enumProp("output_mode", "Output format",
              false, "content", "files_with_matches", "count")
    .boolProp("case_insensitive", "Case-insensitive matching", false)
    .build();
```

Generates a JSON Schema with `type: "object"`, `properties`, `required`, and `additionalProperties: false`.

## Creating a Custom Tool

1. **Implement the `Tool` interface:**

```java
package com.example;

import com.fasterxml.jackson.databind.JsonNode;
import dev.openclaude.tools.*;

public class MyTool implements Tool {

    @Override
    public String name() { return "MyTool"; }

    @Override
    public String description() { return "Does something useful."; }

    @Override
    public JsonNode inputSchema() {
        return SchemaBuilder.object()
            .stringProp("input", "The input value", true)
            .build();
    }

    @Override
    public boolean isReadOnly() { return true; }

    @Override
    public ToolResult execute(JsonNode input, ToolUseContext context) {
        String value = input.get("input").asText();
        return ToolResult.success("Result: " + value);
    }
}
```

2. **Register it** — either directly in `Main.createToolRegistry()`, or via the plugin system (see [Plugin System](plugins.md)).
