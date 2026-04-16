# OpenClaude Java - Technical Documentation

OpenClaude Java is a multi-provider coding agent CLI written in Java 17. It supports 8+ LLM providers, an extensible tool system (built-in + plugins + MCP), an interactive REPL, and a headless server mode.

## Table of Contents

| Document | Description |
|----------|-------------|
| [Architecture](architecture.md) | System design, module graph, data models, request flow |
| [Module Reference](modules.md) | All 10 modules: responsibilities, key classes, public API |
| [LLM Providers](providers.md) | Provider routing, supported providers, client implementations |
| [Tool System](tools.md) | Tool interface, built-in tools, schema builder, extending tools |
| [MCP Integration](mcp.md) | MCP protocol, configuration, server lifecycle |
| [REPL Commands](commands.md) | Slash commands, command interface, adding new commands |
| [Plugin System](plugins.md) | Plugin SPI, discovery, lifecycle, creating plugins |
| [Configuration](configuration.md) | Environment variables, provider detection, directories |
| [Headless Server](headless.md) | JSON-over-TCP protocol, usage, client examples |
| [Development Guide](development.md) | Building, testing, project layout, extending the system |

## Quick Start

```bash
# Build
./gradlew build

# Interactive REPL
./gradlew :cli:run

# Single prompt (print mode)
./gradlew :cli:run --args="-p 'explain this code'"

# Headless server
./gradlew :cli:run --args="--serve"
```

## Tech Stack

- **Language:** Java 17 (records, sealed interfaces, pattern matching)
- **Build:** Gradle with Kotlin DSL
- **JSON:** Jackson 2.18
- **HTTP:** `java.net.http.HttpClient` (no external HTTP library)
- **CLI:** PicoCLI
- **Terminal:** JLine 3
- **Testing:** JUnit 5 (Jupiter)
