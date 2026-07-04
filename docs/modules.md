# Module Reference

OpenClaude Java is organized into 10 Gradle modules. Each module has a focused responsibility and well-defined dependencies.

## core

**Foundation module** — data models, configuration, hooks, permissions, and session management.

| Class | Package | Description |
|-------|---------|-------------|
| `Message` | `core.model` | Sealed interface: `UserMessage`, `AssistantMessage` |
| `ContentBlock` | `core.model` | Sealed interface: `Text`, `Thinking`, `ToolUse`, `Image`, `ToolResult` (block-list content) |
| `StreamEvent` | `core.model` | Sealed interface for LLM streaming events (9 variants) |
| `Role` | `core.model` | Enum: `USER`, `ASSISTANT` |
| `Usage` | `core.model` | Token usage record with accumulation via `add()` |
| `AppConfig` | `core.config` | Env-var driven configuration record with `load()` and `validate()` |
| `ClaudeMdLoader` | `core.config` | Loads `CLAUDE.md` files (user + project) into a system-prompt prefix |
| `ModelAlias` | `core.config` | Resolves `sonnet`/`opus`/`haiku` aliases to full model IDs |
| `HookEvent` | `core.hooks` | Enum of hook lifecycle events (`PreToolUse`, `PostToolUse`, `UserPromptSubmit`, ...) |
| `HookConfig` | `core.hooks` | Parsed hook configuration: matchers + commands per event |
| `HooksConfigLoader` | `core.hooks` | Loads/merges `settings.json#hooks` (user then project) |
| `HookExecutor` | `core.hooks` | Runs hook commands (JSON on stdin/stdout, Claude Code exit-code semantics) |
| `HookDecision` | `core.hooks` | Sealed interface: `Allow`, `Deny`, `Stop`, `ReplaceInput` |
| `PermissionManager` | `core.permissions` | Mode + always-allow/deny rules; `check()` returns `ALLOWED`/`DENIED`/`ASK` |
| `PermissionMode` | `core.permissions` | Enum: `DEFAULT`, `PLAN`, `AUTO_APPROVE`, `AUTO_DENY` |
| `SessionManager` | `core.session` | Conversation state + atomic persistence/resume (`~/.claude/sessions/`) |
| `SessionCodec` | `core.session` | JSON round-trip for the sealed `Message`/`ContentBlock` hierarchy |
| `CostTracker` | `core.session` | Token cost estimation |
| `Store` | `core.state` | Generic state store |

**Dependencies:** Jackson Databind

---

## llm

**LLM provider clients** — unified streaming interface for all supported providers.

| Class | Package | Description |
|-------|---------|-------------|
| `LlmClient` | `llm` | Interface: `streamMessage(request, eventHandler)`, `providerName()` |
| `LlmRequest` | `llm` | Request record: model, systemPrompt, messages, maxTokens, temperature (nullable), tools |
| `LlmClientFactory` | `llm.provider` | Creates the correct client from `AppConfig.provider()` |
| `AnthropicClient` | `llm.anthropic` | Anthropic Messages API with SSE streaming |
| `OpenAIClient` | `llm.openai` | Chat Completions API (OpenAI, Azure, DeepSeek, Groq, etc.) |
| `OllamaClient` | `llm.ollama` | Ollama `/api/chat` with JSONL streaming |

**Dependencies:** core

---

## tools

**Tool system** — interface, registry, built-in tools, and schema builder. Built-in tools are split by domain into `tools.<domain>` packages.

| Class | Package | Description |
|-------|---------|-------------|
| `Tool` | `tools` | Interface: `name()`, `description()`, `inputSchema()`, `execute()`, `isReadOnly()` |
| `ToolRegistry` | `tools` | Registry: `register()`, `registerDeferred()`/`activate()` (ToolSearch), `filteredCopy()`, `toApiToolsArray()` |
| `ToolResult` | `tools` | Execution result record: `content` (list of `ContentBlock`s), `isError` |
| `ToolUseContext` | `tools` | Execution context: shared `workspace`, `readFiles` set, `isAborted` |
| `WorkspaceState` | `tools` | Mutable per-engine cwd; `EnterWorktree`/`ExitWorktree` redirect it at runtime |
| `SchemaBuilder` | `tools` | Fluent builder for JSON Schema objects |
| `BashTool` | `tools.bash` | Shell command execution with enforced timeout and `run_in_background` |
| `BashSandbox` | `tools.bash` | Guardrail rejecting destructive command patterns (bypassable, not a security boundary) |
| `BackgroundProcessManager` | `tools.background` | Shared background-process infra: ring-buffered stdout capture per `process_id` |
| `MonitorTool` | `tools.monitor` | Drains stdout of background processes (`read`) or lists them (`list`) |
| `KillProcessTool` | `tools.kill` | Terminates a tracked background process (graceful then forcible) |
| `FileReadTool` | `tools.fileread` | Multimodal read: text with line numbers, images, PDFs, Jupyter notebooks |
| `FileWriteTool` | `tools.filewrite` | Write files; refuses to overwrite a file not read this session |
| `FileEditTool` | `tools.fileedit` | Exact string replacement; `old_string` must be unique unless `replace_all` |
| `NotebookEditTool` | `tools.notebook` | Jupyter `.ipynb` cell editing: replace/insert/delete/move |
| `GlobTool` | `tools.glob` | Glob-pattern file search, results sorted by mtime |
| `GrepTool` | `tools.grep` | ripgrep-backed search: `content`/`files_with_matches`/`count` modes |
| `WebFetchTool` | `tools.webfetch` | Fetch URL, HTML → markdown, HTTPS upgrade, 5-minute cache |
| `WebSearchTool` | `tools.websearch` | Brave Search API (requires `BRAVE_SEARCH_API_KEY`) |
| `AgentTool` | `tools.agent` | Launches sub-agent: `subagent_type`, `model` override, background, worktree isolation |
| `AgentRunner` / `AgentRunRequest` | `tools.agent` | Interface + request record for sub-agent execution |
| `TodoWriteTool` | `tools.todo` | Session todo list with replace-all semantics (`TodoStore`, `TodoItem`) |
| `ExitPlanModeTool` | `tools.plan` | Plan-mode exit flow: renders plan, asks approval, switches mode back to `DEFAULT` |
| `ToolSearchTool` | `tools.toolsearch` | Activates deferred tools by `select:` or keyword query |
| `PushNotificationTool` | `tools.notify` | Fans out notifications to configured providers (`settings.json#notifications`) |
| `NotificationProvider` + impls | `tools.notify.providers` | `stdout` (default), `libnotify`, generic `webhook`, `slack` |
| `EnterWorktreeTool` / `ExitWorktreeTool` | `tools.worktree` | Move the session into/out of a temporary git worktree (`WorktreeSession`) |

**Dependencies:** core, Jackson

---

## engine

**Agent loop** — the core orchestration that drives the coding agent.

| Class | Package | Description |
|-------|---------|-------------|
| `QueryEngine` | `engine` | Agent loop: send → stream → tools → repeat (max 50 iterations); runs hooks and permission checks around each tool call, appends the plan-mode prompt while `PLAN` is active, supports history continuation and `requestAbort()` (Ctrl+C) |
| `EngineEvent` | `engine` | Sealed interface for engine-level events (incl. `BackgroundAgentDone`, `Aborted`) |
| `AssistantMessageBuilder` | `engine` | Accumulates streaming events into a complete `AssistantMessage` |
| `PermissionHandler` | `engine` | Functional interface resolving `ASK` permission decisions (blocking, e.g. REPL prompt) |
| `SubAgentRunner` | `engine` | Creates isolated `QueryEngine` instances for sub-agents; resolves prompt/tools/model from `SubAgentRegistry`; background + worktree isolation |
| `BackgroundAgentManager` | `engine` | Thread pool for background sub-agents; results polled by the main loop |
| `ContextCompactor` | `engine` | Summarizes old messages to save context window, keeping an API-valid tail |
| `SubAgentDefinition` | `engine.agents` | Sub-agent type record: prompt, tool filter/whitelist, model override |
| `SubAgentRegistry` | `engine.agents` | Built-ins (`general-purpose`, `Explore`, `Plan`) + registered custom agents |
| `MarkdownSubAgentLoader` | `engine.agents` | Loads custom agents from `~/.claude/agents/*.md` and `<cwd>/.claude/agents/*.md` |

**Dependencies:** core, llm, tools

---

## mcp

**MCP client** — Model Context Protocol 2024-11-05 integration.

| Class | Package | Description |
|-------|---------|-------------|
| `McpClientManager` | `mcp` | Manages server connections, initialization, tool discovery |
| `McpToolBridge` | `mcp` | Wraps MCP tools as native `Tool` instances |
| `McpServer` | `mcp` | Sealed interface: `Connected`, `Failed` |
| `McpTransportClient` | `mcp` | Transport abstraction for JSON-RPC communication |
| `McpException` | `mcp` | MCP-specific exception |
| `McpConfigLoader` | `mcp.config` | Loads server configs from `.mcp.json` and `~/.claude/settings.json` |
| `McpServerConfig` | `mcp.config` | Server config record: type, command, args, env, url, headers |
| `StdioTransport` | `mcp.transport` | Stdio-based MCP transport (spawns process) |
| `HttpSseTransport` | `mcp.transport` | HTTP+SSE transport for remote servers; also interoperates with streamable-HTTP servers. A url-only config defaults to SSE |

**Dependencies:** core, Jackson

---

## commands

**REPL slash commands** — command interface, registry, and built-in implementations.

| Class | Package | Description |
|-------|---------|-------------|
| `Command` | `commands` | Interface: `name()`, `description()`, `aliases()`, `execute()` |
| `CommandRegistry` | `commands` | Registry with `find()` and `dispatch()`; built-in aliases are protected |
| `CommandRegistryFactory` | `commands` | Creates registry with all built-in commands |
| `CommandResult` | `commands` | Result record with `Action`: CONTINUE, EXIT, CLEAR, RESET, SUBMIT_PROMPT (+ optional `allowedTools`) |
| `CommandContext` | `commands` | Context: config, tools, permissions, session, cwd, terminal width, registry |
| `CustomCommandLoader` | `commands.loader` | Loads custom commands from `~/.claude/commands/*.md` and `<cwd>/.claude/commands/*.md` (project overrides user) |
| `MarkdownCommand` | `commands.loader` | A markdown-defined command; expands arguments and submits its body as a prompt (optionally tool-restricted via `allowed-tools`) |
| `HelpCommand` | `commands.impl` | `/help` (aliases: `h`, `?`) |
| `ClearCommand` | `commands.impl` | `/clear` (alias: `c`) |
| `ExitCommand` | `commands.impl` | `/exit` (aliases: `q`, `exit`) |
| `ModelCommand` | `commands.impl` | `/model` (alias: `m`) |
| `ToolsCommand` | `commands.impl` | `/tools` |
| `CostCommand` | `commands.impl` | `/cost` |
| `PermissionsCommand` | `commands.impl` | `/permissions [auto\|default\|plan\|deny]` (alias: `perms`) — switches `PermissionMode`, incl. plan mode |
| `ResetCommand` | `commands.impl` | `/reset` (alias: `r`) |
| `StatusCommand` | `commands.impl` | `/status` |
| `CompactCommand` | `commands.impl` | `/compact` (alias: `ctx`) |
| `DiffCommand` | `commands.impl` | `/diff` |
| `ExportCommand` | `commands.impl` | `/export` |
| `DoctorCommand` | `commands.impl` | `/doctor` |
| `DocsCommand` | `commands.impl` | `/docs` |
| `MemoryCommand` | `commands.impl` | `/memory` — shows loaded `CLAUDE.md` files |

**Dependencies:** core, tools

---

## plugins

**Plugin system** — discovery, loading, and lifecycle management.

| Class | Package | Description |
|-------|---------|-------------|
| `Plugin` | `plugins` | SPI interface: `name()`, `version()`, `tools()`, `onLoad()`, `onUnload()` |
| `PluginLoader` | `plugins` | Discovers plugins via ServiceLoader and `~/.claude/plugins/` JARs |

**Dependencies:** tools

---

## grpc

**Headless server** — JSON-over-TCP for embedding in other applications.

| Class | Package | Description |
|-------|---------|-------------|
| `GrpcAgentServer` | `grpc` | TCP server with newline-delimited JSON protocol. Binds loopback by default; `OPENCLAUDE_SERVE_HOST` exposes another interface and then requires `OPENCLAUDE_SERVE_TOKEN` (clients authenticate with `{"type":"auth","token":...}`) |

**Dependencies:** core, engine, llm, tools

---

## tui

**Terminal UI** — REPL loop, streaming display, markdown rendering.

| Class | Package | Description |
|-------|---------|-------------|
| `Repl` | `tui` | Interactive REPL loop: input → command/engine → display. Keeps one engine per session (conversation history + tool state across turns), runs `UserPromptSubmit` hooks, auto-saves the session after every turn, cancels in-flight turns on Ctrl+C, and renders the plan-approval prompt for `ExitPlanMode` |
| `ReplPermissionHandler` | `tui` | Interactive `ASK` resolution: y/a/A/n single-keystroke prompt |
| `TerminalScreen` | `tui` | JLine Terminal wrapper with ANSI output |
| `Ansi` | `tui` | ANSI escape code constants |
| `AgentDisplay` | `tui.widget` | Renders streaming `EngineEvent`s to terminal |
| `Spinner` | `tui.widget` | Animated "Thinking..." spinner |
| `TextInput` | `tui.widget` | JLine-based readline input with history |
| `MarkdownRenderer` | `tui.render` | Inline markdown to ANSI rendering |

**Dependencies:** core, engine, llm, tools, commands, JLine 3

---

## cli

**Entry point** — PicoCLI command that wires all modules and selects execution mode.

| Class | Package | Description |
|-------|---------|-------------|
| `Main` | `cli` | `@Command` entry point: REPL, print (`-p`), or headless (`--serve`/`--port`) mode |

`Main` assembles the runtime: registers built-in tools (including `ExitPlanMode`, `PushNotification`, worktree tools), loads plugins, connects MCP servers (bridged tools registered deferred past `OPENCLAUDE_TOOL_SEARCH_THRESHOLD`, with a `ToolSearch` tool added), loads custom slash commands and sub-agents from `.claude/`, builds the `HookExecutor` from `settings.json`, prefixes the system prompt with `CLAUDE.md` content, and resolves the session.

Notable flags: `-m/--model`, `--system`, `-p/--print`, `--serve`, `--port`, `--no-claude-md`, `--dangerously-skip-permissions` (AUTO_APPROVE), `-c/--continue` (resume latest session), `--resume <sessionId>`.

**Dependencies:** All modules, PicoCLI
