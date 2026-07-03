---
model: claude-haiku-4-5
tags: tool-discovery
---
# Setup
mkdir -p src
printf 'small\n' > src/a.txt
head -c 5000 /dev/urandom | base64 > src/big.txt

# Prompt
List the files under src/ and tell me which one is the largest, with its size.

# Expected
- Discovers files via a listing tool (Glob or Bash ls), not by guessing
- Reads or stats the files to compare sizes
- Answers: src/big.txt is the largest, with a plausible size
