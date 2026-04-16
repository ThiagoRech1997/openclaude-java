# REPL Commands

Slash commands provide in-session control of the REPL — model switching, session management, diagnostics, and more.

## Built-in Commands

| Command | Aliases | Description |
|---------|---------|-------------|
| `/help` | `h`, `?` | Show available commands |
| `/clear` | `c` | Clear the screen |
| `/exit` | `q`, `exit` | Exit the REPL |
| `/model` | `m` | Show or switch the current model |
| `/tools` | | List all registered tools |
| `/cost` | | Show token usage and estimated cost |
| `/permissions` | `perms` | Show permission rules and mode |
| `/reset` | `r` | Reset the conversation (clear messages) |
| `/status` | | Show session status (model, provider, tokens, turns) |
| `/compact` | `ctx` | Compact conversation context (summarize old messages) |
| `/diff` | | Show git diff of working directory |
| `/export` | | Export conversation to file |
| `/doctor` | | Diagnose configuration and connectivity |
| `/docs` | | Open or generate documentation |

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
public record CommandResult(String output, Action action) {
    enum Action { CONTINUE, EXIT, CLEAR, RESET }

    static CommandResult text(String output);   // Display text, continue
    static CommandResult exit();                 // Exit REPL
    static CommandResult clear();               // Clear screen
    static CommandResult reset(String output);  // Reset conversation
}
```

The `Action` tells the REPL what to do after the command:
- **CONTINUE** — print the output and wait for next input
- **EXIT** — terminate the REPL loop
- **CLEAR** — clear the terminal screen
- **RESET** — clear the conversation messages

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
