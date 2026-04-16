# Module Reference

OpenClaude Java is organized into 10 Gradle modules. Each module has a focused responsibility and well-defined dependencies.

## core

**Foundation module** — data models, configuration, permissions, and session management.

| Class | Package | Description |
|-------|---------|-------------|
| `Message` | `core.model` | Sealed interface: `UserMessage`, `AssistantMessage` |
| `ContentBlock` | `core.model` | Sealed interface: `Text`, `Thinking`, `ToolUse`, `ToolResult` |
| `StreamEvent` | `core.model` | Sealed interface for LLM streaming events (9 variants) |
| `Role` | `core.model` | Enum: `USER`, `ASSISTANT` |
| `Usage` | `core.model` | Token usage record with accumulation via `add()` |
| `AppConfig` | `core.config` | Env-var driven configuration record with `load()` and `validate()` |
| `PermissionManager` | `core.permissions` | Tool permission checking with always-allow/deny rules |
| `PermissionMode` | `core.permissions` | Enum: `DEFAULT`, `PLAN`, `AUTO_APPROVE`, `AUTO_DENY` |
| `SessionManager` | `core.session` | Conversation state persistence (`~/.claude/sessions/`) |
| `CostTracker` | `core.session` | Token cost estimation |
| `Store` | `core.state` | Generic state store |

**Dependencies:** Jackson Databind

---

## llm

**LLM provider clients** — unified streaming interface for all supported providers.

| Class | Package | Description |
|-------|---------|-------------|
| `LlmClient` | `llm` | Interface: `streamMessage(request, eventHandler)`, `providerName()` |
| `LlmRequest` | `llm` | Request record: model, systemPrompt, messages, maxTokens, tools |
| `LlmClientFactory` | `llm.provider` | Creates the correct client from `AppConfig.provider()` |
| `AnthropicClient` | `llm.anthropic` | Anthropic Messages API with SSE streaming |
| `OpenAIClient` | `llm.openai` | Chat Completions API (OpenAI, Azure, DeepSeek, Groq, etc.) |
| `OllamaClient` | `llm.ollama` | Ollama `/api/chat` with JSONL streaming |

**Dependencies:** core

---

## tools

**Tool system** — interface, registry, built-in tools, and schema builder.

| Class | Package | Description |
|-------|---------|-------------|
| `Tool` | `tools` | Interface: `name()`, `description()`, `inputSchema()`, `execute()` |
| `ToolRegistry` | `tools` | Central registry with `register()`, `findByName()`, `toApiToolsArray()` |
| `ToolResult` | `tools` | Execution result record: `content`, `isError` |
| `ToolUseContext` | `tools` | Execution context: `workingDirectory` |
| `SchemaBuilder` | `tools` | Fluent builder for JSON Schema objects |
| `BashTool` | `tools.bash` | Shell command execution with timeout |
| `FileReadTool` | `tools.fileread` | Read files with line numbers, offset, and limit |
| `FileWriteTool` | `tools.filewrite` | Write/overwrite files, creating parent directories |
| `FileEditTool` | `tools.fileedit` | Search-and-replace edits within files |
| `GlobTool` | `tools.glob` | File pattern matching with `PathMatcher` |
| `GrepTool` | `tools.grep` | Content search using ripgrep (or fallback to grep) |
| `AgentTool` | `tools.agent` | Launches sub-agent via `AgentRunner` interface |
| `AgentRunner` | `tools.agent` | Functional interface for sub-agent execution |

**Dependencies:** core, Jackson

---

## engine

**Agent loop** — the core orchestration that drives the coding agent.

| Class | Package | Description |
|-------|---------|-------------|
| `QueryEngine` | `engine` | Agent loop: send → stream → tools → repeat (max 50 iterations) |
| `EngineEvent` | `engine` | Sealed interface for engine-level events |
| `AssistantMessageBuilder` | `engine` | Accumulates streaming events into a complete `AssistantMessage` |
| `SubAgentRunner` | `engine` | Creates isolated `QueryEngine` instances for sub-agents |
| `ContextCompactor` | `engine` | Summarizes old messages to save context window |

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

**Dependencies:** core, Jackson

---

## commands

**REPL slash commands** — command interface, registry, and built-in implementations.

| Class | Package | Description |
|-------|---------|-------------|
| `Command` | `commands` | Interface: `name()`, `description()`, `aliases()`, `execute()` |
| `CommandRegistry` | `commands` | Registry with `find()` and `dispatch()` |
| `CommandRegistryFactory` | `commands` | Creates registry with all built-in commands |
| `CommandResult` | `commands` | Result record with `Action`: CONTINUE, EXIT, CLEAR, RESET |
| `CommandContext` | `commands` | Context: config, tools, permissions, session, cwd |
| `HelpCommand` | `commands.impl` | `/help` (aliases: `h`, `?`) |
| `ClearCommand` | `commands.impl` | `/clear` (alias: `c`) |
| `ExitCommand` | `commands.impl` | `/exit` (aliases: `q`, `exit`) |
| `ModelCommand` | `commands.impl` | `/model` (alias: `m`) |
| `ToolsCommand` | `commands.impl` | `/tools` |
| `CostCommand` | `commands.impl` | `/cost` |
| `PermissionsCommand` | `commands.impl` | `/permissions` (alias: `perms`) |
| `ResetCommand` | `commands.impl` | `/reset` (alias: `r`) |
| `StatusCommand` | `commands.impl` | `/status` |
| `CompactCommand` | `commands.impl` | `/compact` (alias: `ctx`) |
| `DiffCommand` | `commands.impl` | `/diff` |
| `ExportCommand` | `commands.impl` | `/export` |
| `DoctorCommand` | `commands.impl` | `/doctor` |
| `DocsCommand` | `commands.impl` | `/docs` |

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
| `GrpcAgentServer` | `grpc` | TCP server with newline-delimited JSON protocol |

**Dependencies:** core, engine, llm, tools

---

## tui

**Terminal UI** — REPL loop, streaming display, markdown rendering.

| Class | Package | Description |
|-------|---------|-------------|
| `Repl` | `tui` | Interactive REPL loop: input → command/engine → display |
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
| `Main` | `cli` | `@Command` entry point: REPL, print, or headless mode |

**Dependencies:** All modules, PicoCLI
