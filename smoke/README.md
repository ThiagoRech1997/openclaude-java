# Smoke Test Harness — OpenClaude vs Claude Code

Runs the same prompt set through **OpenClaude Java** (print mode) and **Claude Code**
(`claude -p`) with the same model, and produces a side-by-side markdown report.
This is the "parity CI": not a pass/fail suite, but a report read by a human before
releases or big changes.

## Requirements

- A provider configured for OpenClaude (`ANTHROPIC_API_KEY`, or Ollama running, etc.).
- Optional: the `claude` CLI on PATH — cases run OpenClaude-only when absent.
- `JAVA_HOME` pointing at a JDK 17 (see CLAUDE.md).

## Usage

```bash
# Everything, default model per prompt frontmatter
smoke/runner.sh

# Only cases tagged multi-step, forcing a model
smoke/runner.sh --filter multi-step --model claude-haiku-4-5

# Skip the Claude Code side (e.g. air-gapped environment)
smoke/runner.sh --skip-claude
```

Outputs land in `smoke/out/<timestamp>/`:
- `<case>/openclaude.txt`, `<case>/claude.txt` — raw captured output
- `report.md` — side-by-side comparison with extracted tool calls

## Adding cases

Create `smoke/prompts/<name>.md`:

```markdown
---
model: claude-haiku-4-5
tags: tool-discovery
---
# Setup
echo "hello" > sample.txt

# Prompt
Read sample.txt and tell me what it says.

# Expected
- Calls the Read tool (not cat)
- Reports the file content
```

- `# Setup` (optional): bash lines executed in the case's scratch directory first.
- `# Prompt`: what both CLIs receive.
- `# Expected`: behaviors the reviewer checks in the report — never sent to the model.

Each case runs in its own scratch directory so file-mutating prompts don't
interfere with the repo or each other.
