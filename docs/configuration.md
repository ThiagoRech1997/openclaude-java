# Configuration Reference

OpenClaude Java is configured primarily through environment variables. There are no config files for provider settings — only MCP server configs use JSON files.

## Environment Variables

### General

| Variable | Default | Description |
|----------|---------|-------------|
| `OPENCLAUDE_MODEL` | (provider-specific) | Override the model for any provider |
| `OPENCLAUDE_MAX_TOKENS` | `16384` | Maximum output tokens per request |

### Anthropic

| Variable | Default | Description |
|----------|---------|-------------|
| `ANTHROPIC_API_KEY` | (required) | Anthropic API key |
| `ANTHROPIC_BASE_URL` | `https://api.anthropic.com` | API base URL |
| `ANTHROPIC_MODEL` | `claude-sonnet-4-20250514` | Model name |

### OpenAI

| Variable | Default | Description |
|----------|---------|-------------|
| `OPENAI_API_KEY` | (required) | OpenAI API key |
| `OPENAI_BASE_URL` | `https://api.openai.com/v1` | API base URL |
| `OPENAI_MODEL` | `gpt-4o` | Model name |
| `CLAUDE_CODE_USE_OPENAI` | `0` | Force OpenAI provider (`1` or `true`) |

### Ollama

| Variable | Default | Description |
|----------|---------|-------------|
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama server URL |
| `OLLAMA_HOST` | (none) | Alternative to `OLLAMA_BASE_URL` |
| `OLLAMA_MODEL` | `llama3.1` | Model name |

### OpenRouter

| Variable | Default | Description |
|----------|---------|-------------|
| `OPENROUTER_API_KEY` | (required) | OpenRouter API key |
| `OPENROUTER_BASE_URL` | `https://openrouter.ai/api/v1` | API base URL |

### GitHub Models

| Variable | Default | Description |
|----------|---------|-------------|
| `GITHUB_TOKEN` / `GH_TOKEN` | (required) | GitHub personal access token |
| `GITHUB_MODELS_BASE_URL` | `https://models.inference.ai.azure.com/v1` | API base URL |
| `CLAUDE_CODE_USE_GITHUB` | `0` | Enable GitHub Models (`1` or `true`) |

### Other OpenAI-Compatible Providers

For Azure, DeepSeek, Groq, Mistral, Together, and local servers (LM Studio, etc.), set `OPENAI_API_KEY` and `OPENAI_BASE_URL` to the provider's endpoint. The provider is auto-detected from the URL.

## AppConfig

All configuration is loaded into a single record:

```java
public record AppConfig(
    String apiKey,
    String model,
    String baseUrl,
    String provider,
    int maxTokens
)
```

- `AppConfig.load()` — reads environment variables and returns the config
- `AppConfig.validate()` — throws if API key is missing (except for Ollama)

## Provider Detection Order

See [LLM Providers - Provider Auto-Detection](providers.md#provider-auto-detection) for the full detection algorithm.

## Permission Modes

The `PermissionManager` controls which tools can execute:

| Mode | Behavior |
|------|----------|
| `DEFAULT` | Ask for dangerous ops, auto-allow read-only tools |
| `PLAN` | Read-only only — no mutation tools |
| `AUTO_APPROVE` | Allow all tools without prompting |
| `AUTO_DENY` | Deny all non-read-only tools |

### Permission Rules

Rules are evaluated in this order:
1. **Always-deny** — tool is in the deny list -> DENIED
2. **Always-allow** — tool is in the allow list -> ALLOWED
3. **Mode** — mode determines the default behavior

Manage permissions with the `/permissions` REPL command.

## User Directories

| Path | Description |
|------|-------------|
| `~/.claude/sessions/` | Session JSON files (conversation persistence) |
| `~/.claude/plugins/` | Plugin JARs (discovered at startup) |
| `~/.claude/settings.json` | User-level MCP server configuration |

## MCP Configuration Files

| Path | Description |
|------|-------------|
| `~/.claude/settings.json` | User-level MCP server configs (under `mcpServers` key) |
| `.mcp.json` | Project-local MCP server configs (in working directory) |

Project-local configs override user-level configs for servers with the same name. See [MCP Integration](mcp.md) for details.

## CLI Options

| Option | Description |
|--------|-------------|
| `[prompt]` | Initial prompt (omit for interactive REPL) |
| `-m`, `--model` | Override model |
| `--system` | Custom system prompt |
| `-p`, `--print` | Print mode: single prompt, no REPL |
| `--serve` | Start headless JSON-over-TCP server |
| `--port` | Server port (default: 9818) |
| `--help` | Show help |
| `--version` | Show version |
