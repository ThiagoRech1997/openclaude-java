# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

OpenClaude Java is a multi-provider coding agent CLI written in Java 17 — a reimplementation of OpenClaude (TypeScript). It supports 8+ LLM providers, an extensible tool system (built-in + plugins + MCP), an interactive REPL, and a headless server mode.

## Build & Run

```bash
# Build all modules
./gradlew build

# Run the CLI (interactive REPL mode)
./gradlew :cli:run

# Run in print mode (single prompt, no REPL)
./gradlew :cli:run --args="-p 'your prompt here'"

# Run in headless server mode (JSON-over-TCP on port 9818)
./gradlew :cli:run --args="--serve"

# Run tests
./gradlew test

# Run tests for a specific module
./gradlew :core:test
./gradlew :engine:test

# Run a single test class
./gradlew :core:test --tests "dev.openclaude.core.SomeTest"

# Clean build
./gradlew clean build
```

## Architecture

**10-module Gradle project** (`build.gradle.kts` with Kotlin DSL). All modules live under the root and follow `src/main/java/dev/openclaude/<module>/` layout.

### Module Dependency Graph

```
cli → tui → engine → tools → core
                  ↘ llm → core
                  ↘ mcp → core
                  ↘ commands → core
       grpc → engine
       plugins → tools
```

### Module Responsibilities

- **core** — Sealed data models (`Message`, `ContentBlock`, `StreamEvent` as sealed interfaces with records), `AppConfig` (env-var driven), `PermissionManager`, `SessionManager`. This is the foundation everything depends on.
- **llm** — `LlmClient` interface with three implementations routed by `LlmClientFactory`: `AnthropicClient` (SSE streaming), `OpenAIClient` (covers OpenAI/Azure/Deepseek/Groq/Mistral/Together/OpenRouter/GitHub Models), `OllamaClient` (JSONL streaming). All use Java 11+ HttpClient directly — no external HTTP library.
- **tools** — `Tool` interface (`name()`, `description()`, `inputSchema()`, `execute()`), `ToolRegistry`, and `ToolUseContext` (passed to every `execute()` — tracks per-session state such as files read, used by `FileWriteTool`). Built-in tools are split by domain under `dev.openclaude.tools.<domain>`: `bash/`, `fileread/`, `filewrite/`, `fileedit/`, `glob/`, `grep/`, `webfetch/`, `websearch/`, `agent/`, `monitor/`, `kill/`, `background/` (shared `BackgroundProcessManager`). See **Built-in Tools** below for per-tool contracts.
- **engine** — `QueryEngine` runs the agent loop (up to 50 iterations): send messages → stream LLM response → if tool calls, execute them and loop. Also contains `ContextCompactor` and `SubAgentRunner`.
- **mcp** — MCP 2024-11-05 client. `McpClientManager` manages server connections (stdio transport), `McpToolBridge` wraps remote MCP tools as native `Tool` instances. Tool names are prefixed `mcp__<server>__<tool>`.
- **commands** — REPL slash commands (`/help`, `/model`, `/clear`, `/cost`, `/tools`, `/diff`, `/doctor`, etc.) via `CommandRegistry`. Each command implements `Command` and returns `CommandResult` with an action (EXIT, CLEAR, RESET, CONTINUE).
- **plugins** — `PluginLoader` discovers plugins via Java ServiceLoader and JAR scanning in `~/.claude/plugins/`.
- **grpc** — `GrpcAgentServer`: headless JSON-over-TCP server (default port 9818).
- **tui** — Terminal UI with JLine 3. `Repl` is the main loop, `AgentDisplay` renders streaming output, `TextInput` handles readline-like input, `MarkdownRenderer` formats markdown.
- **cli** — `Main.java` is the PicoCLI entry point that wires everything together and selects mode (REPL, print, or headless).

### Built-in Tools

Non-obvious contracts worth knowing before editing or adding tools:

- **BashTool** — executes shell commands. A **sandbox** validates the command before running and rejects destructive patterns (e.g. `rm -rf /`, fork bombs, `dd` to block devices, shutdown). The sandbox is a guardrail, **not** a security boundary: callers may pass `dangerouslyDisableSandbox` to bypass. Supports `run_in_background` — returns a `process_id` instead of blocking, with stdout captured by `BackgroundProcessManager`.
- **FileReadTool** — multimodal: text (with line numbers, `offset`/`limit`), images (PNG/JPG/GIF/WebP returned as base64 `Image` blocks), PDFs (text extraction, max 20 pages via `pages` param), and Jupyter notebooks (`.ipynb` cells + outputs + inline images). Returns `ContentBlock`s, not raw strings.
- **FileWriteTool** — **safety contract**: an existing file cannot be overwritten unless it has been read in the current session (tracked via `ToolUseContext.readFiles()`). New files are always allowed. Prefer `FileEditTool` for modifying existing files.
- **FileEditTool** — exact string replacement in a file; fails if `old_string` is not unique unless `replace_all` is set.
- **GlobTool** — glob-pattern file search; results sorted by mtime.
- **GrepTool** — ripgrep-backed search. Three output modes (`content`, `files_with_matches`, `count`) and params: `-i`, `-n`, `-A`/`-B`/`-C`, `multiline`, `offset`, `type`, `head_limit` (default 250).
- **WebFetchTool** — fetches a URL, converts HTML → markdown. Auto-upgrades to HTTPS, truncates at 80KB, caches responses for 5 minutes.
- **WebSearchTool** — Brave Search API. Requires `BRAVE_SEARCH_API_KEY` env var; auto-disabled if absent. Returns title/URL/snippet, cached 5 minutes.
- **AgentTool** — launches a sub-agent via `SubAgentRunner`. Supports `subagent_type` (e.g. `general-purpose`, `Explore`, `Plan`), `model` override (`sonnet`/`opus`/`haiku`), and `run_in_background` (async, completion notification).
- **MonitorTool** — observes background processes. `action="read"` drains new stdout lines for a `process_id` (optional regex filter); `action="list"` shows status of all tracked processes.
- **KillProcessTool** — terminates a tracked background process (graceful then forcible).

### Background Process Model

`BackgroundProcessManager` (in `tools/background/`) is shared infrastructure used by `BashTool` (`run_in_background`), `MonitorTool`, and `KillProcessTool`. Each process gets a `process_id`; stdout is captured line-by-line into a 10K-line bounded ring buffer by a daemon reader thread. If lines are dropped (buffer overflow), a wrap marker is emitted on the next drain. Exited processes are auto-cleaned on the second drain.

### Sealed Data Model Hierarchy

These sealed interfaces are the backbone of the type system — understand them before modifying engine or LLM code:

- **Message** → `UserMessage`, `AssistantMessage`
- **ContentBlock** → `Text`, `Thinking`, `ToolUse`, `ToolResult`
- **StreamEvent** → `MessageStart`, `TextDelta`, `ThinkingDelta`, `ToolUseStart`, `ToolInputDelta`, `ContentBlockStop`, `MessageComplete`, `MessageDelta`, `Error`

### Provider Routing (`LlmClientFactory`)

`AppConfig` determines the provider string, then `LlmClientFactory` selects the client:
- `"anthropic"` → `AnthropicClient` (SSE streaming, Messages API)
- `"openai"`, `"azure"`, `"deepseek"`, `"groq"`, `"mistral"`, `"together"`, `"local"`, `"openrouter"`, `"github"` → `OpenAIClient` (Chat Completions API)
- `"ollama"` → `OllamaClient` (JSONL streaming, `/api/chat`)
- Fallback: if base URL contains `anthropic.com` → Anthropic, otherwise OpenAI-compatible

### Key Patterns

- **Sealed interfaces + records** for all data models — exhaustive pattern matching, no class hierarchies.
- **Jackson 2.18** for all JSON (de)serialization. `JsonNode` is used extensively for tool schemas and LLM API payloads.
- **No external HTTP library** — all LLM clients use `java.net.http.HttpClient` directly.
- **No linting/static analysis** configured — no Checkstyle, SpotBugs, or similar plugins.
- **No CI/CD** — no GitHub Actions or other pipeline config exists yet.
- **Configuration env vars**: `OPENCLAUDE_MAX_TOKENS` (default 16384), `OPENCLAUDE_MODEL`, provider-specific API keys (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, etc.), `BRAVE_SEARCH_API_KEY` (enables `WebSearchTool`).
- **User data directories**: `~/.claude/sessions/` (session JSON), `~/.claude/plugins/` (plugin JARs).

## Testing

JUnit 5 (Jupiter). Tests go in `<module>/src/test/java/`. The test framework is configured in the root `build.gradle.kts` with `useJUnitPlatform()`.
