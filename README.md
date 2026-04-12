# OpenClaude Java

Um agente de codificação CLI multi-provider escrito em Java 17 — reimplementação do [OpenClaude](https://github.com/anthropics/claude-code) (TypeScript) com arquitetura modular, sistema extensível de ferramentas e suporte a 8+ provedores de LLM.

## Funcionalidades

- **Multi-provider** — Anthropic, OpenAI, Ollama, OpenRouter, GitHub Models, Azure, Deepseek, Groq, Mistral, Together e provedores locais
- **Sistema de ferramentas extensível** — Bash, leitura/escrita/edição de arquivos, glob, grep, sub-agentes
- **REPL interativo** — Terminal UI com JLine, markdown rendering, histórico de comandos
- **Model Context Protocol (MCP)** — Integração com servidores MCP via transporte stdio
- **Plugins** — Descoberta automática via ServiceLoader e JARs em `~/.claude/plugins/`
- **Modo headless** — Servidor JSON-over-TCP para integração programática
- **Gerenciamento de sessões** — Persistência de conversas, tracking de custo por tokens

## Requisitos

- **Java 17+**
- **Gradle** (wrapper incluído)
- Chave de API de pelo menos um provedor LLM

## Início Rápido

```bash
# Clone o repositório
git clone https://github.com/seu-usuario/openclaude-java.git
cd openclaude-java

# Configure a chave de API (exemplo com Anthropic)
export ANTHROPIC_API_KEY="sua-chave-aqui"

# Build
./gradlew build

# Execute o REPL interativo
./gradlew :cli:run

# Ou execute com um prompt direto
./gradlew :cli:run --args="-p 'Explique este código'"
```

## Uso

```
openclaude [opções] [prompt]
```

### Opções CLI

| Opção | Descrição |
|-------|-----------|
| `-m, --model <MODEL>` | Modelo a usar (sobrescreve o configurado) |
| `--system <PROMPT>` | System prompt customizado |
| `-p, --print` | Modo print: prompt único, sem REPL |
| `--serve` | Iniciar servidor headless JSON-over-TCP |
| `--port <PORT>` | Porta do servidor headless (padrão: 9818) |

### Modos de Execução

- **REPL interativo** (padrão) — Interface de terminal completa com comandos
- **Prompt único** — Passa o prompt como argumento, recebe resposta e encerra
- **Print mode** (`-p`) — Similar ao prompt único, saída otimizada para piping
- **Servidor headless** (`--serve`) — Servidor TCP para integração com IDEs e ferramentas

### Comandos do REPL

| Comando | Aliases | Descrição |
|---------|---------|-----------|
| `/help` | `h`, `?` | Mostra comandos disponíveis |
| `/model` | | Mostra modelo e provedor atual |
| `/tools` | | Lista ferramentas disponíveis |
| `/cost` | | Mostra uso de tokens e custo estimado |
| `/permissions` | `perms` | Gerencia permissões de ferramentas |
| `/status` | | Mostra status da sessão |
| `/diff` | | Mostra git diff de mudanças não commitadas |
| `/export` | | Exporta conversa para markdown |
| `/doctor` | | Verifica ambiente e configuração |
| `/compact` | | Compacta contexto da conversa |
| `/clear` | `cls` | Limpa a tela |
| `/reset` | `new` | Reinicia a conversa |
| `/exit` | `quit`, `q` | Sai do REPL |

## Provedores LLM

### Configuração por Variáveis de Ambiente

| Provedor | Variável de API Key | Modelo Padrão |
|----------|-------------------|---------------|
| **Anthropic** (padrão) | `ANTHROPIC_API_KEY` | `claude-sonnet-4-20250514` |
| **OpenAI** | `OPENAI_API_KEY` + `CLAUDE_CODE_USE_OPENAI=1` | `gpt-4o` |
| **Ollama** | (sem chave, local) | `llama3.1` |
| **OpenRouter** | `OPENROUTER_API_KEY` | `anthropic/claude-sonnet-4-20250514` |
| **GitHub Models** | `GITHUB_TOKEN` + `CLAUDE_CODE_USE_GITHUB=1` | `gpt-4o` |

Provedores OpenAI-compatíveis (Azure, Deepseek, Groq, Mistral, Together) são detectados automaticamente pela base URL.

### Variáveis Comuns

| Variável | Descrição | Padrão |
|----------|-----------|--------|
| `OPENCLAUDE_MODEL` | Sobrescreve o modelo padrão | (específico do provedor) |
| `OPENCLAUDE_MAX_TOKENS` | Máximo de tokens na resposta | `16384` |
| `ANTHROPIC_BASE_URL` | URL base da API Anthropic | `https://api.anthropic.com` |
| `OPENAI_BASE_URL` | URL base da API OpenAI | `https://api.openai.com/v1` |
| `OLLAMA_BASE_URL` | URL base do Ollama | `http://localhost:11434` |

## Arquitetura

Projeto Gradle multi-módulo com 10 módulos:

```
cli        → Ponto de entrada (PicoCLI), wiring dos módulos
tui        → Terminal UI, REPL, rendering (JLine 3)
engine     → Loop do agente, compactação de contexto, sub-agentes
llm        → Abstração de provedores LLM (Anthropic, OpenAI, Ollama)
tools      → Interface Tool, registry, ferramentas built-in
mcp        → Cliente MCP, bridge de ferramentas
commands   → Comandos slash do REPL
plugins    → Descoberta e carregamento de plugins
grpc       → Servidor headless JSON-over-TCP
core       → Modelos de dados, config, permissões, sessões
```

### Ferramentas Built-in

| Ferramenta | Descrição |
|------------|-----------|
| `BashTool` | Executa comandos bash/cmd (timeout 2min, trunca saída em 512KB) |
| `FileReadTool` | Lê conteúdo de arquivos |
| `FileWriteTool` | Escreve arquivos (cria diretórios se necessário) |
| `FileEditTool` | Edita seções específicas de arquivos |
| `GlobTool` | Busca arquivos por padrão glob |
| `GrepTool` | Busca conteúdo com regex (estilo ripgrep) |
| `AgentTool` | Cria e executa sub-agentes |

### Extensibilidade

- **Plugins**: implemente a interface `Plugin` e coloque o JAR em `~/.claude/plugins/`
- **MCP**: configure servidores MCP no arquivo `.mcp.json` do projeto
- **Provedores LLM**: qualquer API compatível com OpenAI pode ser usada via `OPENAI_BASE_URL`

## Dependências Principais

| Dependência | Versão | Uso |
|------------|--------|-----|
| Jackson | 2.18.3 | Serialização JSON |
| PicoCLI | 4.7.6 | Framework CLI |
| JLine | 3.27.1 | Terminal/REPL |
| JUnit 5 | 5.11.4 | Testes |

## Desenvolvimento

```bash
# Build completo
./gradlew build

# Testes
./gradlew test                              # todos os módulos
./gradlew :core:test                        # módulo específico
./gradlew :core:test --tests "*.SomeTest"   # classe específica

# Clean build
./gradlew clean build
```

## Diretórios do Usuário

| Diretório | Conteúdo |
|-----------|----------|
| `~/.claude/sessions/` | Sessões salvas em JSON |
| `~/.claude/plugins/` | JARs de plugins |
