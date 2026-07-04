# Configuration Reference

OpenClaude Java is configured primarily through environment variables. Provider settings have no config files; `settings.json` (user-level and project-local) configures MCP servers, hooks, and notifications.

## Environment Variables

### General

| Variable | Default | Description |
|----------|---------|-------------|
| `OPENCLAUDE_MODEL` | (provider-specific) | Override the model for any provider |
| `OPENCLAUDE_MAX_TOKENS` | `16384` | Maximum output tokens per request |
| `OPENCLAUDE_TOOL_SEARCH_THRESHOLD` | `25` | Tool-count threshold above which MCP tool schemas are deferred and loaded on demand via the `ToolSearch` tool. `0` disables deferral |
| `OPENCLAUDE_DISABLE_CLAUDE_MD` | (unset) | Disable auto-loading of `CLAUDE.md` files (`1` or `true`) — see [CLAUDE.md Auto-Load](#claudemd-auto-load) |
| `BRAVE_SEARCH_API_KEY` | (unset) | Enables the `WebSearch` tool (Brave Search API); the tool errors without it |

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

### Headless Server (`--serve`)

| Variable | Default | Description |
|----------|---------|-------------|
| `OPENCLAUDE_SERVE_HOST` | `127.0.0.1` | Interface to bind. The server binds loopback by default |
| `OPENCLAUDE_SERVE_TOKEN` | (unset) | Auth token for clients. **Required** when binding a non-loopback host — the server refuses to start otherwise. Clients must authenticate first with `{"type": "auth", "token": "..."}` |

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
| `PLAN` | Plan mode — see below |
| `AUTO_APPROVE` | Allow all tools without prompting |
| `AUTO_DENY` | Deny all non-read-only tools |

### Plan Mode

`/permissions plan` does two things:

1. **Permission gate** — only read-only tools may run; mutation tools (Write, Edit, Bash, ...) are denied.
2. **Behavior change** — a dedicated system prompt instructs the model to research with
   read-only tools and produce an implementation plan (steps, files affected, trade-offs)
   instead of making changes.

When the plan is complete, the model calls the `ExitPlanMode` tool. The REPL renders the
plan and asks for approval: approving switches the session back to `DEFAULT` so the model
can implement; rejecting keeps plan mode active and the model revises. In non-interactive
modes (`-p`, `--serve`) there is no approval UI, so `ExitPlanMode` refuses and the session
stays in plan mode.

### Permission Rules

Rules are evaluated in this order:
1. **Always-deny** — tool is in the deny list -> DENIED
2. **Always-allow** — tool is in the allow list -> ALLOWED
3. **Mode** — mode determines the default behavior

Manage permissions with the `/permissions` REPL command.

## User Directories

| Path | Description |
|------|-------------|
| `~/.claude/sessions/` | Session JSON files (conversation persistence, `--continue`/`--resume`) |
| `~/.claude/plugins/` | Plugin JARs (discovered at startup) |
| `~/.claude/settings.json` | User-level settings: MCP servers, hooks, notifications |
| `~/.claude/CLAUDE.md` | User-level memory, auto-loaded into the system prompt |
| `~/.claude/commands/` | User-global custom slash commands (`*.md`) — see [REPL Commands](commands.md#custom-slash-commands) |
| `~/.claude/agents/` | User-global custom sub-agent definitions (`*.md`) |

## MCP Configuration Files

| Path | Description |
|------|-------------|
| `~/.claude/settings.json` | User-level MCP server configs (under `mcpServers` key) |
| `.mcp.json` | Project-local MCP server configs (in working directory) |

Project-local configs override user-level configs for servers with the same name. See [MCP Integration](mcp.md) for details.

## Hooks

Hooks run shell commands at lifecycle points of the agent loop. They are configured under the `hooks` key of `settings.json` and follow the Claude Code hook protocol:

| Path | Description |
|------|-------------|
| `~/.claude/settings.json` | User-level hooks |
| `.claude/settings.json` | Project-local hooks (in working directory) |

Both files are merged — matchers from both run (project entries after user entries).

```json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Bash|Write",
        "hooks": [
          { "type": "command", "command": "./check.sh", "timeout": 60 }
        ]
      }
    ]
  }
}
```

- **Events wired today**: `PreToolUse`, `PostToolUse`, `UserPromptSubmit`. (The enum also declares `SessionStart`, `SessionEnd`, `Stop`, `SubagentStop`, `Notification` for future use — entries under those keys are parsed but never fire.)
- **`matcher`** — a regex matched against the tool name; omit it to match every tool.
- **`timeout`** — per-command timeout in seconds (default: 60).
- **Input** — the hook receives a JSON payload on stdin (`hook_event_name`, `session_id`, `cwd`, plus `tool_name`/`tool_input` for tool events, `tool_response` for `PostToolUse`, or `prompt` for `UserPromptSubmit`).
- **Exit codes** — exit code `2` is a *blocking* error (stderr becomes the reason); any other non-zero exit is non-blocking (stderr is surfaced, execution continues).
- **Stdout JSON** — a hook may print a decision such as `{"decision": "block", "reason": "..."}` or `{"continue": false}`. `PostToolUse` hooks cannot deny — a `block` there is downgraded to feedback for the model; only `continue: false` stops the loop.

## Notifications

The `PushNotification` tool fans out to the providers configured under the `notifications` key of `~/.claude/settings.json`:

```json
{
  "notifications": {
    "providers": ["stdout", "libnotify", "webhook", "slack"],
    "webhookUrl": "https://example.com/notify",
    "webhookHeaders": { "Authorization": "Bearer ${MY_TOKEN}" },
    "slackWebhookUrl": "https://hooks.slack.com/services/..."
  }
}
```

| Provider | Description |
|----------|-------------|
| `stdout` | Print to the terminal (default when nothing is configured) |
| `libnotify` | Desktop notification via `notify-send` |
| `webhook` | Generic HTTP POST to `webhookUrl` with optional `webhookHeaders` |
| `slack` | Slack incoming webhook (`slackWebhookUrl`) |

URL and header values support `${ENV_VAR}` substitution, so secrets can stay out of the file. `webhook`/`slack` entries without a URL are skipped; if no provider ends up configured, `stdout` is used.

## CLAUDE.md Auto-Load

At startup, `CLAUDE.md` files are concatenated (most general first) and prepended to the system prompt:

1. `~/.claude/CLAUDE.md` — user-level memory
2. `<cwd>/.claude/CLAUDE.md` — project-local memory
3. `<cwd>/CLAUDE.md` — project instructions, checked into the codebase

Disable with the `--no-claude-md` CLI flag or the `OPENCLAUDE_DISABLE_CLAUDE_MD` env var. The `/memory` REPL command shows which files were loaded.

## Custom Sub-Agents

Markdown files define additional sub-agent types for the `Agent` tool (`subagent_type`):

| Path | Description |
|------|-------------|
| `~/.claude/agents/*.md` | User-global sub-agents |
| `.claude/agents/*.md` | Project-local sub-agents |

Precedence when names collide: project > user > built-in. The file body becomes the sub-agent's system prompt; frontmatter keys:

```markdown
---
name: reviewer            # defaults to filename without .md (case preserved)
description: Reviews diffs for bugs
tools: [Read, Grep, Glob]  # inline list only; restricts the sub-agent's tools
model: haiku               # optional alias (sonnet/opus/haiku) or full model ID
---
System prompt for the sub-agent goes here.
```

## CLI Options

| Option | Description |
|--------|-------------|
| `[prompt]` | Initial prompt (omit for interactive REPL) |
| `-m`, `--model` | Override model |
| `--system` | Custom system prompt |
| `-p`, `--print` | Print mode: single prompt, no REPL |
| `--serve` | Start headless JSON-over-TCP server |
| `--port` | Server port (default: 9818) |
| `-c`, `--continue` | Resume the most recent session (REPL mode) |
| `--resume <sessionId>` | Resume a saved session by ID (REPL mode) |
| `--no-claude-md` | Disable auto-loading of CLAUDE.md files |
| `--dangerously-skip-permissions` | Auto-approve every tool call for the session |
| `--help` | Show help |
| `--version` | Show version |
