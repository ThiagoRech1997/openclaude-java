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
public record ToolUseContext(
    WorkspaceState workspace,     // Mutable per-engine cwd (worktree-aware)
    boolean isAborted,
    Set<Path> readFiles           // Files read/written this session
) {
    public Path workingDirectory();  // Always the *current* directory
}
```

- `workspace` is a shared, mutable `WorkspaceState` holding the directory tools operate in. Normally the original working directory, but `EnterWorktree`/`ExitWorktree` can redirect the whole session into a temporary git worktree at runtime — `workingDirectory()` always reflects the current location.
- `readFiles` is a shared set of absolute paths that have been read (or written) during the session. `Write` consults it to refuse overwriting files the agent has not observed. Paths are recorded symlink-resolved (`toRealPath()`) so the guard compares against what the OS actually touches.

## ToolRegistry

The `ToolRegistry` is the central collection of all available tools:

```java
public final class ToolRegistry {
    void register(Tool tool);
    void registerDeferred(Tool tool);  // Withheld from the API array until activated
    boolean isDeferred(String name);
    void activate(String name);        // Called by ToolSearch
    List<Tool> deferredTools();        // Deferred and not yet activated
    Optional<Tool> findByName(String name);
    List<Tool> enabledTools();
    ArrayNode toApiToolsArray();  // Generates LLM tool definitions (skips deferred)
    int size();
}
```

**Deferred tools:** a tool registered via `registerDeferred` stays out of the API tools array (saving prompt tokens) until `activate()` is called — normally by the [ToolSearch](#toolsearch) tool. The engine rebuilds the array every iteration, so activation takes effect on the next LLM call.

Tools are registered in `Main.createToolRegistry()` in this order:
1. Built-in tools (Bash, Monitor, KillProcess, Read, Write, Edit, NotebookEdit, Glob, Grep, WebFetch, WebSearch, TodoWrite, ExitPlanMode, PushNotification, EnterWorktree, ExitWorktree)
2. AgentTool (sub-agents)
3. Plugin tools (from `PluginLoader`)
4. MCP tools (from `McpToolBridge`) — if the resulting catalog would exceed `OPENCLAUDE_TOOL_SEARCH_THRESHOLD` (default 25) tools, MCP tools are registered *deferred* and a `ToolSearch` tool is added to load their schemas on demand

## Built-in Tools

### Bash

**Name:** `Bash` | **Read-only:** No

Executes shell commands via `ProcessBuilder`.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `command` | string | yes | Shell command to execute |
| `description` | string | yes | Clear description of what the command does |
| `timeout` | integer | no | Timeout in milliseconds (max 600000) |
| `dangerouslyDisableSandbox` | boolean | no | Bypass the command sandbox |
| `run_in_background` | boolean | no | Run detached; returns a `process_id` |

**Behavior:**
- Runs in the current working directory (worktree-aware)
- A sandbox (`BashSandbox`) validates the command first and rejects destructive patterns (e.g. `rm -rf /`, fork bombs). It is a guardrail, not a security boundary — `dangerouslyDisableSandbox` bypasses it
- Captures stdout and stderr (merged)
- Enforces timeout (default 120s, max 10min). Output is drained on a separate reader thread so the timeout fires even for hung commands; on timeout the process is forcibly killed and the partial output is returned with the error
- Truncates output beyond 512KB
- Returns exit code in error messages for non-zero exits
- With `run_in_background`, returns immediately with a `process_id`; stdout is captured by `BackgroundProcessManager` and drained via the Monitor tool

### Read

**Name:** `Read` | **Read-only:** Yes

Reads file contents with line numbers.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file_path` | string | yes | Absolute or relative path |
| `offset` | integer | no | Starting line (0-based, must be >= 0) |
| `limit` | integer | no | Number of lines to read (default 2000, must be > 0) |

**Output format:** `1\tfirst line\n2\tsecond line\n...` (1-based line numbers with tab separator)

**Behavior:**
- Relative paths resolve against the current working directory
- Text is decoded as lossy UTF-8 — invalid bytes are replaced instead of failing the whole read (Latin-1 files, stray binary)
- Successful reads are recorded in `ToolUseContext.readFiles()` (symlink-resolved), which unlocks overwriting the file with `Write`

### Write

**Name:** `Write` | **Read-only:** No

Writes content to a file, creating parent directories if needed.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file_path` | string | yes | Absolute or relative path |
| `content` | string | yes | File content to write |

**Behavior:**
- **Safety contract:** an existing file cannot be overwritten unless it was read in the current session (tracked via `ToolUseContext.readFiles()`). New files are always allowed
- The guard compares symlink-resolved paths, so `link/../file` cannot slip past it
- Creates parent directories if needed

### Edit

**Name:** `Edit` | **Read-only:** No

Performs exact string replacement within a file.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file_path` | string | yes | Absolute or relative path |
| `old_string` | string | yes | Text to find; must be unique in the file |
| `new_string` | string | yes | Replacement text |
| `replace_all` | boolean | no | Replace every occurrence (default false) |

**Behavior:** Fails if `old_string` is not found, or occurs more than once without `replace_all`.

### NotebookEdit

**Name:** `NotebookEdit` | **Read-only:** No

Edits a Jupyter notebook (`.ipynb`) cell-by-cell.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `notebook_path` | string | yes | Path to the `.ipynb` file |
| `edit_mode` | string | no | `replace` (default), `insert`, `delete`, or `move` |
| `cell_index` | integer | varies | 0-based cell index. Required for replace/delete/move; for insert, the new cell lands here (omit to append) |
| `new_index` | integer | for move | Destination 0-based index |
| `new_source` | string | for replace/insert | New cell source |
| `cell_type` | string | no | `code` or `markdown`. Required for insert (default `code`); optional for replace (keeps existing type) |

**Behavior:**
- Preserves notebook metadata and untouched cells (including their outputs)
- Replacing a `code` cell's source clears that cell's `outputs` and `execution_count` to avoid stale results
- Uses Jackson to parse/serialize; writes pretty-printed JSON back to disk

### Glob

**Name:** `Glob` | **Read-only:** Yes

Finds files matching a glob pattern.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `pattern` | string | yes | Glob pattern (e.g., `**/*.java`) |
| `path` | string | no | Directory to search in |
| `limit` | int | no | Max results. Default 500. Pass `0` for unlimited. |

**Behavior:**
- Uses `FileSystems.getPathMatcher`
- Relative `path` resolves against the current working directory (matters for worktree sub-agents)
- Skips `.git`, `node_modules`, `build`, `.gradle`, `target`
- Sorted by modification time (most recent first)
- Limits to 500 results by default (override via `limit`; `0` = unlimited). Truncation happens *after* sorting, so it keeps the newest N matches — not the first N in walk order

### Grep

**Name:** `Grep` | **Read-only:** Yes

Searches file contents using regex patterns.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `pattern` | string | yes | Regex pattern |
| `path` | string | no | File or directory to search in |
| `glob` | string | no | File pattern filter (e.g., `*.java`) |
| `output_mode` | string | no | `content`, `files_with_matches`, or `count` |
| `-i` | boolean | no | Case-insensitive search |
| `-n` | boolean | no | Show line numbers (default true, content mode only) |
| `-A` / `-B` / `-C` | integer | no | Context lines after/before/around matches (content mode only) |
| `multiline` | boolean | no | Patterns can span lines (`rg -U --multiline-dotall`) |
| `type` | string | no | File type filter (`rg --type`: java, js, py, ...) |
| `offset` | integer | no | Skip first N results before `head_limit` (default 0) |
| `head_limit` | integer | no | Limit results (default 250; `0` = unlimited) |

**Behavior:**
- Prefers `rg` (ripgrep) if available, falls back to system `grep -E`
- The pattern is passed via `-e` and paths after `--`, so patterns starting with `-` are never parsed as flags

### Agent

**Name:** `Agent` | **Read-only:** No

Launches a sub-agent to handle complex tasks.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `prompt` | string | yes | Task for the sub-agent |
| `description` | string | no | Short task description |

**Behavior:** Creates an isolated `QueryEngine` via `SubAgentRunner`. The sub-agent has access to the same tools and LLM but runs in a separate conversation thread.

### TodoWrite

**Name:** `TodoWrite` | **Read-only:** No

Session-scoped structured task list for tracking multi-step work.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `todos` | array | yes | Complete replacement list of `{content, activeForm, status}` items |

Each item: `content` (imperative, e.g. "Run tests"), `activeForm` (present-continuous, e.g. "Running tests"), `status` (`pending`, `in_progress`, or `completed`).

**Behavior:**
- **Replace-all semantics:** every call sends the full list, which atomically replaces the state held in the shared `TodoStore` — there is no add/update-single; any item omitted is removed
- Validates that at most one item is `in_progress`
- State is in-memory only (lost on restart)

### ExitPlanMode

**Name:** `ExitPlanMode` | **Read-only:** Yes

Signals that the plan is complete and asks the user to approve it. Only usable while `PermissionMode.PLAN` is active.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `plan` | string | yes | The full implementation plan in markdown |

**Behavior:**
- Errors if the session is not in plan mode
- The interactive layer (REPL) injects an `ApprovalHandler` that renders the plan and prompts the user. Approving switches the permission mode back to `DEFAULT`; rejecting keeps `PLAN` so the model revises
- Without an approval handler (print/serve mode) it refuses to exit plan mode
- `isReadOnly()` is `true` so the plan-mode permission gate lets it run

### ToolSearch

**Name:** `ToolSearch` | **Read-only:** Yes

Loads the full schemas of deferred tools on demand. Only registered when the tool catalog exceeds `OPENCLAUDE_TOOL_SEARCH_THRESHOLD` (default 25) — in that case MCP tools are registered deferred and appear only as a name+summary index inside ToolSearch's own (dynamically generated) description.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `query` | string | yes | `select:Name1,Name2` for exact names, or free-text keywords matched against names and descriptions |
| `max_results` | integer | no | Max tools to load for keyword queries (default 5) |

**Behavior:**
- Matched tools are activated in the `ToolRegistry`; the engine rebuilds the API tools array every iteration, so they become callable on the next LLM call
- Returns each loaded tool's full `{name, description, input_schema}` definition
- Unknown names in a `select:` query are reported but do not fail the call

### PushNotification

**Name:** `PushNotification` | **Read-only:** Yes

Sends a notification to the user through every configured channel.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `title` | string | yes | Short notification title |
| `body` | string | no | Notification body text |
| `level` | string | no | `info` (default), `warning`, or `error` |

**Behavior:**
- Fans out to the providers configured in `~/.claude/settings.json#notifications`: `stdout` (default), `libnotify`, generic `webhook` (`webhookUrl` + `webhookHeaders`), and `slack` (`slackWebhookUrl`)
- URL and header values support `${ENV_VAR}` substitution
- Succeeds if at least one channel delivers; failed channels are reported alongside

### EnterWorktree

**Name:** `EnterWorktree` | **Read-only:** No

Creates a temporary git worktree and switches the session into it — all subsequent tool calls run there until `ExitWorktree`.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `branch` | string | no | Branch name to create (auto-generated if omitted) |
| `base` | string | no | Ref to fork from (default: `HEAD`) |

**Behavior:**
- Mutates the shared `WorkspaceState` (`ToolUseContext.workspace()`), so every tool's `workingDirectory()` now points at the worktree
- Errors if already inside a worktree
- Commits on the worktree branch are visible in the main repository

### ExitWorktree

**Name:** `ExitWorktree` | **Read-only:** No

Leaves the active worktree and restores the original working directory.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `keep_changes` | boolean | no | Keep the worktree and its branch if it contains changes (default true); `false` discards it regardless |

**Behavior:**
- With changes (and `keep_changes` true), the worktree and branch are kept and reported; without changes, the worktree is removed
- `keep_changes: false` removes the worktree and its branch unconditionally
- Errors if not inside a worktree

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
