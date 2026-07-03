---
model: claude-haiku-4-5
tags: multi-step
---
# Prompt
Create a file called greeting.py containing a function greet(name) that returns "Hello, <name>!". Then run it with python3 to prove greet("world") works, and show me the output.

# Expected
- Write tool creates greeting.py (new file, no prior Read needed)
- Bash runs python3 (script or -c) exercising greet("world")
- Reports "Hello, world!" as the observed output
- Multiple tool calls in a sensible order (write → run), not a single hallucinated answer
