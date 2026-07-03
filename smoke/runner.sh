#!/usr/bin/env bash
# Smoke harness: run each prompt through OpenClaude (print mode) and Claude Code
# (claude -p), capture outputs, and emit a side-by-side markdown report.
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROMPTS_DIR="$ROOT/smoke/prompts"
OUT_ROOT="$ROOT/smoke/out/$(date +%Y%m%d-%H%M%S)"
TIMEOUT_S=300
FILTER=""
MODEL_OVERRIDE=""
SKIP_CLAUDE=0
SKIP_OPENCLAUDE=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --filter) FILTER="$2"; shift 2 ;;
    --model) MODEL_OVERRIDE="$2"; shift 2 ;;
    --skip-claude) SKIP_CLAUDE=1; shift ;;
    --skip-openclaude) SKIP_OPENCLAUDE=1; shift ;;
    --timeout) TIMEOUT_S="$2"; shift 2 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

# --- build OpenClaude once ---------------------------------------------------
OPENCLAUDE_BIN="$ROOT/cli/build/install/cli/bin/cli"
if [[ $SKIP_OPENCLAUDE -eq 0 ]]; then
  echo ">> building OpenClaude (installDist)..."
  (cd "$ROOT" && ./gradlew -q :cli:installDist) || { echo "build failed" >&2; exit 1; }
fi

CLAUDE_BIN="$(command -v claude || true)"
if [[ $SKIP_CLAUDE -eq 0 && -z "$CLAUDE_BIN" ]]; then
  echo ">> claude CLI not found on PATH — running OpenClaude side only"
  SKIP_CLAUDE=1
fi

mkdir -p "$OUT_ROOT"
REPORT="$OUT_ROOT/report.md"
{
  echo "# Smoke report — $(date -Is)"
  echo
  echo "| | |"
  echo "|---|---|"
  echo "| OpenClaude | $([[ $SKIP_OPENCLAUDE -eq 1 ]] && echo skipped || echo "$OPENCLAUDE_BIN") |"
  echo "| Claude Code | $([[ $SKIP_CLAUDE -eq 1 ]] && echo skipped || echo "$CLAUDE_BIN") |"
  echo "| Model override | ${MODEL_OVERRIDE:-per-case frontmatter} |"
} > "$REPORT"

# --- helpers ------------------------------------------------------------------
frontmatter_value() { # file key
  awk -v key="$2" '
    NR==1 && $0=="---" {inside=1; next}
    inside && $0=="---" {exit}
    inside && $0 ~ "^"key":" {sub("^"key":[[:space:]]*", ""); print; exit}
  ' "$1"
}

section() { # file section-name  → prints the body of "# <name>" until next "# "
  awk -v name="$2" '
    $0 == "# "name {inside=1; next}
    inside && /^# / {exit}
    inside {print}
  ' "$1"
}

extract_tool_calls() { # output-file → best-effort list of tool invocations
  grep -oE '(⚡|🔧|📖|✏️|📝|🔍|🔎) [A-Za-z_]+' "$1" 2>/dev/null | awk '{print $2}' | uniq
}

# --- run cases ------------------------------------------------------------------
shopt -s nullglob
CASES=("$PROMPTS_DIR"/*.md)
[[ ${#CASES[@]} -eq 0 ]] && { echo "no prompts in $PROMPTS_DIR" >&2; exit 1; }

for case_file in "${CASES[@]}"; do
  name="$(basename "$case_file" .md)"
  tags="$(frontmatter_value "$case_file" tags)"
  if [[ -n "$FILTER" && "$tags" != *"$FILTER"* ]]; then
    continue
  fi

  model="${MODEL_OVERRIDE:-$(frontmatter_value "$case_file" model)}"
  prompt="$(section "$case_file" Prompt)"
  expected="$(section "$case_file" Expected)"
  setup="$(section "$case_file" Setup)"

  echo ">> case: $name (tags: ${tags:-none}, model: ${model:-default})"
  case_out="$OUT_ROOT/$name"
  mkdir -p "$case_out"

  run_side() { # side bin...
    local side="$1"; shift
    local scratch="$case_out/workdir-$side"
    mkdir -p "$scratch"
    if [[ -n "$setup" ]]; then
      (cd "$scratch" && bash -c "$setup") >/dev/null 2>&1
    fi
    (cd "$scratch" && timeout "$TIMEOUT_S" "$@") \
        > "$case_out/$side.txt" 2>&1
    local status=$?
    [[ $status -eq 124 ]] && echo "[harness: timed out after ${TIMEOUT_S}s]" >> "$case_out/$side.txt"
    return 0
  }

  if [[ $SKIP_OPENCLAUDE -eq 0 ]]; then
    if [[ -n "$model" ]]; then
      run_side openclaude "$OPENCLAUDE_BIN" -p -m "$model" "$prompt"
    else
      run_side openclaude "$OPENCLAUDE_BIN" -p "$prompt"
    fi
  fi
  if [[ $SKIP_CLAUDE -eq 0 ]]; then
    if [[ -n "$model" ]]; then
      run_side claude "$CLAUDE_BIN" -p --model "$model" "$prompt"
    else
      run_side claude "$CLAUDE_BIN" -p "$prompt"
    fi
  fi

  # --- report section ---
  {
    echo
    echo "## $name"
    echo
    echo "**Tags:** ${tags:-—} · **Model:** ${model:-default}"
    echo
    echo "**Prompt:**"
    echo '```'
    echo "$prompt"
    echo '```'
    echo
    echo "**Expected:**"
    echo "$expected"
    echo
    for side in openclaude claude; do
      out="$case_out/$side.txt"
      [[ -f "$out" ]] || continue
      echo "<details><summary><b>$side</b> output</summary>"
      echo
      echo '```'
      head -c 20000 "$out"
      echo '```'
      echo "</details>"
      echo
      tools="$(extract_tool_calls "$out")"
      if [[ -n "$tools" ]]; then
        echo "**$side tool calls:** $(echo "$tools" | tr '\n' ' ')"
        echo
      fi
    done
    if [[ $SKIP_OPENCLAUDE -eq 0 && $SKIP_CLAUDE -eq 0 ]]; then
      oc_tools="$(extract_tool_calls "$case_out/openclaude.txt" | sort)"
      cc_tools="$(extract_tool_calls "$case_out/claude.txt" | sort)"
      if [[ "$oc_tools" != "$cc_tools" ]]; then
        echo "> ⚠️ **Divergence:** tool sets differ between the two sides."
        echo
      fi
    fi
  } >> "$REPORT"
done

echo
echo ">> report: $REPORT"
