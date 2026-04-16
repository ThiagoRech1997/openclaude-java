# Development Guide

## Prerequisites

- **Java 17+** (the project uses records, sealed interfaces, and pattern matching)
- **Gradle** (wrapper included — use `./gradlew`)

## Build & Run

```bash
# Build all modules
./gradlew build

# Run the CLI (interactive REPL)
./gradlew :cli:run

# Print mode (single prompt)
./gradlew :cli:run --args="-p 'your prompt here'"

# Headless server mode
./gradlew :cli:run --args="--serve"

# Specify a model
./gradlew :cli:run --args="-m gpt-4o"

# Clean build
./gradlew clean build
```

## Testing

JUnit 5 (Jupiter) is configured in the root `build.gradle.kts`.

```bash
# Run all tests
./gradlew test

# Run tests for a specific module
./gradlew :core:test
./gradlew :engine:test
./gradlew :tools:test

# Run a single test class
./gradlew :core:test --tests "dev.openclaude.core.model.UsageTest"
```

Tests go in `<module>/src/test/java/dev/openclaude/<module>/`.

## Project Layout

```
openclaude-java/
|-- build.gradle.kts          # Root build config (Java 17, JUnit 5)
|-- settings.gradle.kts        # Module declarations
|-- gradlew / gradlew.bat      # Gradle wrapper
|
|-- core/
|   `-- src/main/java/dev/openclaude/core/
|       |-- config/            # AppConfig
|       |-- model/             # Message, ContentBlock, StreamEvent, Usage, Role
|       |-- permissions/       # PermissionManager, PermissionMode
|       |-- session/           # SessionManager, CostTracker
|       `-- state/             # Store
|
|-- llm/
|   `-- src/main/java/dev/openclaude/llm/
|       |-- LlmClient.java    # Interface
|       |-- LlmRequest.java   # Request record
|       |-- anthropic/         # AnthropicClient (SSE)
|       |-- openai/            # OpenAIClient (Chat Completions)
|       |-- ollama/            # OllamaClient (JSONL)
|       `-- provider/          # LlmClientFactory
|
|-- tools/
|   `-- src/main/java/dev/openclaude/tools/
|       |-- Tool.java          # Interface
|       |-- ToolRegistry.java  # Registry
|       |-- ToolResult.java    # Result record
|       |-- ToolUseContext.java # Context record
|       |-- SchemaBuilder.java # JSON Schema builder
|       |-- bash/              # BashTool
|       |-- fileread/          # FileReadTool
|       |-- filewrite/         # FileWriteTool
|       |-- fileedit/          # FileEditTool
|       |-- glob/              # GlobTool
|       |-- grep/              # GrepTool
|       `-- agent/             # AgentTool, AgentRunner
|
|-- engine/
|   `-- src/main/java/dev/openclaude/engine/
|       |-- QueryEngine.java           # Agent loop
|       |-- EngineEvent.java           # Event hierarchy
|       |-- AssistantMessageBuilder.java
|       |-- SubAgentRunner.java
|       `-- ContextCompactor.java
|
|-- mcp/
|   `-- src/main/java/dev/openclaude/mcp/
|       |-- McpClientManager.java  # Connection manager
|       |-- McpToolBridge.java     # Tool adapter
|       |-- McpServer.java         # Server states
|       |-- McpTransportClient.java
|       |-- McpException.java
|       |-- config/                # McpConfigLoader, McpServerConfig
|       `-- transport/             # StdioTransport
|
|-- commands/
|   `-- src/main/java/dev/openclaude/commands/
|       |-- Command.java           # Interface
|       |-- CommandRegistry.java
|       |-- CommandRegistryFactory.java
|       |-- CommandResult.java
|       |-- CommandContext.java
|       `-- impl/                  # All built-in command implementations
|
|-- plugins/
|   `-- src/main/java/dev/openclaude/plugins/
|       |-- Plugin.java        # SPI interface
|       `-- PluginLoader.java
|
|-- grpc/
|   `-- src/main/java/dev/openclaude/grpc/
|       `-- GrpcAgentServer.java   # Headless TCP server
|
|-- tui/
|   `-- src/main/java/dev/openclaude/tui/
|       |-- Repl.java              # REPL loop
|       |-- TerminalScreen.java    # JLine Terminal wrapper
|       |-- Ansi.java              # ANSI escape codes
|       |-- render/                # MarkdownRenderer
|       `-- widget/                # AgentDisplay, Spinner, TextInput
|
`-- cli/
    `-- src/main/java/dev/openclaude/cli/
        `-- Main.java              # PicoCLI entry point
```

## Dependencies

Defined in the root `build.gradle.kts` and module-level `build.gradle.kts` files:

| Dependency | Version | Used By |
|-----------|---------|---------|
| Jackson Databind | 2.18.3 | core, llm, tools, engine, mcp, grpc |
| JLine 3 | 3.x | tui |
| PicoCLI | 4.x | cli |
| JUnit Jupiter | 5.11.4 | all modules (test) |

No external HTTP library — `java.net.http.HttpClient` is used directly.

## Adding a New Module

1. Create the directory: `<module>/src/main/java/dev/openclaude/<module>/`
2. Create `<module>/build.gradle.kts` with dependencies
3. Add `include("<module>")` to `settings.gradle.kts`
4. Add the module dependency where needed (e.g., `implementation(project(":mymodule"))`)

## Adding a New Tool

See [Tool System - Creating a Custom Tool](tools.md#creating-a-custom-tool).

## Adding a New Command

See [REPL Commands - Adding a New Command](commands.md#adding-a-new-command).

## Adding a New LLM Provider

See [LLM Providers - Adding a New Provider](providers.md#adding-a-new-provider).

## Adding a Plugin

See [Plugin System - Creating a Plugin](plugins.md#creating-a-plugin).

## Code Conventions

- **Sealed interfaces + records** for all data models
- **Jackson `JsonNode`** for JSON manipulation
- **No external HTTP library** — `java.net.http.HttpClient` only
- **No linting/static analysis** configured
- **No CI/CD** pipeline configured
- **UTF-8** encoding for all source files
- **Java 17** language features: records, sealed interfaces, pattern matching in `switch`
