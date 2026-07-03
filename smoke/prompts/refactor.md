---
model: claude-haiku-4-5
tags: refactor
---
# Setup
cat > calc.py <<'EOF'
def c(a, b, o):
    if o == "+":
        return a + b
    if o == "-":
        return a - b
    if o == "*":
        return a * b
    if o == "/":
        return a / b
EOF

# Prompt
Rename the function c in calc.py to calculate and give its parameters descriptive names. Keep behavior identical.

# Expected
- Reads calc.py before modifying it (read-before-write contract)
- Uses Edit (or Write after Read) — not a blind overwrite
- Resulting file has calculate(left, right, operator)-style naming, same logic
- No unrelated changes
