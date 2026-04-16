# LLM Providers

OpenClaude Java supports 10+ LLM providers through a unified `LlmClient` interface. All clients use `java.net.http.HttpClient` directly with streaming.

## Provider Routing

`LlmClientFactory.create(AppConfig)` selects the client based on `config.provider()`:

```java
switch (config.provider()) {
    case "anthropic"                                          -> AnthropicClient
    case "ollama"                                             -> OllamaClient
    case "openai", "azure", "deepseek", "groq", "mistral",
         "together", "local", "openrouter", "github"          -> OpenAIClient
    default -> guess by base URL (anthropic.com? -> Anthropic : OpenAI)
}
```

## Supported Providers

| Provider | Client | Protocol | API Key Env Var |
|----------|--------|----------|-----------------|
| Anthropic | `AnthropicClient` | Messages API + SSE | `ANTHROPIC_API_KEY` |
| OpenAI | `OpenAIClient` | Chat Completions + SSE | `OPENAI_API_KEY` |
| Azure OpenAI | `OpenAIClient` | Chat Completions + SSE | `OPENAI_API_KEY` |
| DeepSeek | `OpenAIClient` | Chat Completions + SSE | `OPENAI_API_KEY` |
| Groq | `OpenAIClient` | Chat Completions + SSE | `OPENAI_API_KEY` |
| Mistral | `OpenAIClient` | Chat Completions + SSE | `OPENAI_API_KEY` |
| Together | `OpenAIClient` | Chat Completions + SSE | `OPENAI_API_KEY` |
| OpenRouter | `OpenAIClient` | Chat Completions + SSE | `OPENROUTER_API_KEY` |
| GitHub Models | `OpenAIClient` | Chat Completions + SSE | `GITHUB_TOKEN` |
| Ollama | `OllamaClient` | `/api/chat` + JSONL | (none) |
| Local (LM Studio, etc.) | `OpenAIClient` | Chat Completions + SSE | `OPENAI_API_KEY` |

## Provider Auto-Detection

`AppConfig.load()` detects the provider from environment variables in this order:

1. **Ollama** — if `OLLAMA_BASE_URL` or `OLLAMA_HOST` is set
2. **OpenRouter** — if `OPENROUTER_API_KEY` is set
3. **GitHub Models** — if `GITHUB_TOKEN` + `CLAUDE_CODE_USE_GITHUB=1`
4. **OpenAI** — if `CLAUDE_CODE_USE_OPENAI=1`, or `OPENAI_API_KEY` set without `ANTHROPIC_API_KEY`
5. **Sub-detection for OpenAI** — if the base URL contains `openai.azure.com` -> azure, `deepseek.com` -> deepseek, `groq.com` -> groq, `mistral.ai` -> mistral, `together.xyz` -> together, `localhost`/`127.0.0.1` -> local
6. **Default: Anthropic** — uses `ANTHROPIC_API_KEY`

## Client Implementations

### AnthropicClient

**Protocol:** Server-Sent Events (SSE) on `POST /v1/messages`

**Headers:**
- `x-api-key: <apiKey>`
- `anthropic-version: 2023-06-01`
- `content-type: application/json`

**Request body:**
```json
{
  "model": "claude-sonnet-4-20250514",
  "max_tokens": 16384,
  "stream": true,
  "system": "You are a helpful coding assistant...",
  "messages": [...],
  "tools": [...]
}
```

**SSE event types:**
- `message_start` -> `StreamEvent.MessageStart`
- `content_block_start` -> `StreamEvent.ToolUseStart` (for tool_use blocks)
- `content_block_delta` -> `StreamEvent.TextDelta` / `ThinkingDelta` / `ToolInputDelta`
- `content_block_stop` -> `StreamEvent.ContentBlockStop`
- `message_delta` -> `StreamEvent.MessageDelta` (stop_reason + usage)
- `message_stop` -> (stream ends)
- `error` -> `StreamEvent.Error`

**Features:** Thinking/reasoning content, cache usage tracking, native tool_use format.

### OpenAIClient

**Protocol:** SSE streaming on `POST /chat/completions`

**Headers:**
- `Authorization: Bearer <apiKey>`
- `content-type: application/json`

**Key differences from Anthropic:**
- System prompt is a `role: system` message (not a top-level field)
- Tools use `function` format with `function_call` in deltas
- Tool results are `role: tool` messages with `tool_call_id`
- No native thinking/reasoning content support

**Covers:** OpenAI, Azure, DeepSeek, Groq, Mistral, Together, OpenRouter, GitHub Models, LM Studio, and any OpenAI-compatible API.

### OllamaClient

**Protocol:** JSONL streaming on `POST /api/chat`

**No authentication required.** Each line is a JSON object:
```json
{"model": "llama3.1", "created_at": "...", "message": {"role": "assistant", "content": "..."}, "done": false}
```

The final line has `"done": true` with total token counts.

## Default Models

| Provider | Default Model |
|----------|--------------|
| Anthropic | `claude-sonnet-4-20250514` |
| OpenAI | `gpt-4o` |
| Ollama | `llama3.1` |
| OpenRouter | `anthropic/claude-sonnet-4-20250514` |
| GitHub Models | `gpt-4o` |

Override with `OPENCLAUDE_MODEL` or provider-specific vars (`ANTHROPIC_MODEL`, `OPENAI_MODEL`, `OLLAMA_MODEL`).

## Adding a New Provider

If the provider uses an OpenAI-compatible API:

1. Set `OPENAI_API_KEY` and `OPENAI_BASE_URL` to the provider's endpoint
2. Optionally add URL detection in `AppConfig.detectOpenAIProvider()` for automatic provider identification

If the provider has a unique API:

1. Create a new class implementing `LlmClient` in a new package under `llm/`
2. Implement `streamMessage()` with the provider's streaming protocol
3. Add a case to `LlmClientFactory.create()`
4. Add detection logic in `AppConfig.load()` if desired
