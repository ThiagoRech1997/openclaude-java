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
- **tools** — `Tool` interface (`name()`, `description()`, `inputSchema()`, `execute()`) and `ToolRegistry`. Built-in tools: `BashTool`, `FileReadTool`, `FileWriteTool`, `FileEditTool`, `GlobTool`, `GrepTool`.
- **engine** — `QueryEngine` runs the agent loop (up to 50 iterations): send messages → stream LLM response → if tool calls, execute them and loop. Also contains `ContextCompactor` and `SubAgentRunner`.
- **mcp** — MCP 2024-11-05 client. `McpClientManager` manages server connections (stdio transport), `McpToolBridge` wraps remote MCP tools as native `Tool` instances. Tool names are prefixed `mcp__<server>__<tool>`.
- **commands** — REPL slash commands (`/help`, `/model`, `/clear`, `/cost`, `/tools`, `/diff`, `/doctor`, etc.) via `CommandRegistry`. Each command implements `Command` and returns `CommandResult` with an action (EXIT, CLEAR, RESET, CONTINUE).
- **plugins** — `PluginLoader` discovers plugins via Java ServiceLoader and JAR scanning in `~/.claude/plugins/`.
- **grpc** — `GrpcAgentServer`: headless JSON-over-TCP server (default port 9818).
- **tui** — Terminal UI with JLine 3. `Repl` is the main loop, `AgentDisplay` renders streaming output, `TextInput` handles readline-like input, `MarkdownRenderer` formats markdown.
- **cli** — `Main.java` is the PicoCLI entry point that wires everything together and selects mode (REPL, print, or headless).

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
- **Configuration env vars**: `OPENCLAUDE_MAX_TOKENS` (default 16384), `OPENCLAUDE_MODEL`, provider-specific API keys (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, etc.).
- **User data directories**: `~/.claude/sessions/` (session JSON), `~/.claude/plugins/` (plugin JARs).

## Testing

JUnit 5 (Jupiter). Tests go in `<module>/src/test/java/`. The test framework is configured in the root `build.gradle.kts` with `useJUnitPlatform()`.
