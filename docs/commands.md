# REPL Commands

Slash commands provide in-session control of the REPL — model switching, session management, diagnostics, and more.

## Built-in Commands

| Command | Aliases | Description |
|---------|---------|-------------|
| `/help` | `h`, `?` | Show available commands |
| `/clear` | `cls` | Clear the screen |
| `/exit` | `quit`, `q` | Exit the REPL |
| `/model` | | Show or switch the current model |
| `/tools` | | List all registered tools |
| `/cost` | | Show token usage and estimated cost |
| `/permissions` | `perms` | Show permission rules and mode |
| `/reset` | `new` | Reset the conversation (clear messages) |
| `/status` | | Show session status (model, provider, tokens, turns) |
| `/compact` | | Compact conversation context (summarize old messages) |
| `/diff` | | Show git diff of working directory |
| `/export` | | Export conversation to file |
| `/doctor` | | Diagnose configuration and connectivity |
| `/docs` | `documentation` | Open or generate documentation |
| `/memory` | `claude-md` | Show loaded CLAUDE.md files |

## Custom Slash Commands

Markdown files become slash commands — no Java required. The loader scans two directories (non-recursive):

| Path | Source tag |
|------|------------|
| `~/.claude/commands/*.md` | `user` |
| `.claude/commands/*.md` (in working directory) | `project` |

The command name is the filename without `.md` (lowercased). Project-local commands override user-global ones with the same name; collisions with a built-in command name **or alias** are skipped with a warning (a repo-provided `q.md` cannot hijack `/q`).

### File Format

```markdown
---
description: Summarize a file
argument-hint: <path>
allowed-tools: [Read, Grep]
---
Summarize the file at $ARGUMENTS in three bullet points.
```

- **Body** — invoking `/<name> args` substitutes `$ARGUMENTS` with the raw argument string and submits the result to the LLM as a user prompt (`CommandResult.Action.SUBMIT_PROMPT`).
- **`description`** — shown in `/help` (default: `(custom command)`).
- **`argument-hint`** — appended to the description as `[<hint>]`.
- **`allowed-tools`** — inline list only (block lists are not supported). When non-empty, the submitted turn runs with the tool registry restricted to those tool names.

Frontmatter is optional; without it the whole file is the prompt body.

## Command Interface

```java
// commands/src/main/java/dev/openclaude/commands/Command.java
public interface Command {
    String name();                    // Command name without slash
    String description();             // Shown in /help
    default String[] aliases() { return new String[0]; }
    CommandResult execute(String args, CommandContext context);
}
```

### CommandResult

```java
public record CommandResult(String output, Action action, List<String> allowedTools) {
    enum Action { CONTINUE, EXIT, CLEAR, RESET, SUBMIT_PROMPT }

    static CommandResult text(String output);   // Display text, continue
    static CommandResult exit();                 // Exit REPL
    static CommandResult clear();               // Clear screen
    static CommandResult reset(String output);  // Reset conversation
    static CommandResult submitPrompt(String prompt);
    static CommandResult submitPrompt(String prompt, List<String> allowedTools);
}
```

The `Action` tells the REPL what to do after the command:
- **CONTINUE** — print the output and wait for next input
- **EXIT** — terminate the REPL loop
- **CLEAR** — clear the terminal screen
- **RESET** — clear the conversation messages
- **SUBMIT_PROMPT** — feed `output` to the LLM as a user prompt (used by custom slash commands)

`allowedTools` only applies to `SUBMIT_PROMPT`: when non-null and non-empty, the submitted turn runs with the tool registry restricted to those tool names.

### CommandContext

```java
public record CommandContext(
    AppConfig config,
    ToolRegistry toolRegistry,
    PermissionManager permissions,
    SessionManager session,
    Path workingDirectory,
    int terminalWidth,
    CommandRegistry commandRegistry
) {}
```

Provides access to all session state needed by commands.

## CommandRegistry

```java
public final class CommandRegistry {
    void register(Command command);           // Register command + aliases
    Optional<Command> find(String name);      // Find by name or alias
    CommandResult dispatch(String input, CommandContext context);  // Parse + execute
    Collection<Command> allCommands();
}
```

**Dispatch flow:** The registry parses the input string, splits the command name from arguments, looks up the command (by name or alias), and calls `execute()`.

`CommandRegistryFactory.create()` registers the built-ins; `create(Path cwd)` additionally discovers [custom markdown commands](#custom-slash-commands) in `~/.claude/commands/` and `<cwd>/.claude/commands/`.

## Adding a New Command

1. **Create a class** in `commands/src/main/java/dev/openclaude/commands/impl/`:

```java
package dev.openclaude.commands.impl;

import dev.openclaude.commands.*;

public class MyCommand implements Command {

    @Override
    public String name() { return "mycommand"; }

    @Override
    public String description() { return "Does something useful."; }

    @Override
    public String[] aliases() { return new String[]{"mc"}; }

    @Override
    public CommandResult execute(String args, CommandContext context) {
        // Access session, config, tools, etc. via context
        return CommandResult.text("Output here");
    }
}
```

2. **Register it** in `CommandRegistryFactory.create()`:

```java
registry.register(new MyCommand());
```
