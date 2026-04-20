package dev.openclaude.tui;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openclaude.core.permissions.PermissionManager;
import dev.openclaude.core.permissions.PermissionMode;
import dev.openclaude.engine.PermissionHandler;
import org.jline.terminal.Attributes;

import java.io.IOException;

/**
 * Interactive permission handler used by the REPL: renders a 4-option prompt
 * on the terminal and reads a single keystroke to resolve an ASK decision.
 *
 * <p>Keys:
 * <ul>
 *   <li>{@code y} — allow once</li>
 *   <li>{@code a} — always allow this tool (adds to {@link PermissionManager} allow-list)</li>
 *   <li>{@code A} — always allow any tool (switches to AUTO_APPROVE)</li>
 *   <li>anything else (n, Ctrl-C, Ctrl-D, EOF, unknown char) — deny once</li>
 * </ul>
 */
public class ReplPermissionHandler implements PermissionHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_PREVIEW = 200;

    private final TerminalScreen screen;
    private final PermissionManager permissions;

    public ReplPermissionHandler(TerminalScreen screen, PermissionManager permissions) {
        this.screen = screen;
        this.permissions = permissions;
    }

    @Override
    public PermissionManager.PermissionDecision ask(String toolName, JsonNode input, boolean isReadOnly) {
        screen.println();
        screen.println("  " + Ansi.YELLOW + "Allow " + Ansi.BOLD + toolName + Ansi.RESET
                + Ansi.YELLOW + " to run?" + Ansi.RESET);
        screen.println("  " + Ansi.DIM + "input: " + previewInput(input) + Ansi.RESET);
        screen.println("  " + Ansi.GREEN + "(y)" + Ansi.RESET + " yes, once   "
                + Ansi.GREEN + "(a)" + Ansi.RESET + " always allow this tool");
        screen.println("  " + Ansi.GREEN + "(A)" + Ansi.RESET + " always allow ANY tool   "
                + Ansi.RED + "(n)" + Ansi.RESET + " no, deny once  " + Ansi.DIM + "[default]" + Ansi.RESET);
        screen.print("  > ");
        screen.flush();

        int c = readChar();
        screen.println();

        return switch (c) {
            case 'y', 'Y' -> PermissionManager.PermissionDecision.ALLOWED;
            case 'a' -> {
                permissions.addAlwaysAllow(toolName);
                screen.println("  " + Ansi.DIM + "(always allowing " + toolName + " for this session)" + Ansi.RESET);
                yield PermissionManager.PermissionDecision.ALLOWED;
            }
            case 'A' -> {
                permissions.setMode(PermissionMode.AUTO_APPROVE);
                screen.println("  " + Ansi.DIM + "(auto-approving all tools for this session)" + Ansi.RESET);
                yield PermissionManager.PermissionDecision.ALLOWED;
            }
            default -> {
                screen.println("  " + Ansi.RED + "(denied)" + Ansi.RESET);
                yield PermissionManager.PermissionDecision.DENIED;
            }
        };
    }

    private int readChar() {
        Attributes prev = screen.enterRawMode();
        try {
            return screen.getTerminal().reader().read();
        } catch (IOException e) {
            return -1;
        } finally {
            screen.restoreAttributes(prev);
        }
    }

    private String previewInput(JsonNode input) {
        if (input == null || input.isNull()) return "{}";
        String json;
        try {
            json = MAPPER.writeValueAsString(input);
        } catch (JsonProcessingException e) {
            json = input.toString();
        }
        if (json.length() <= MAX_PREVIEW) return json;
        return json.substring(0, MAX_PREVIEW) + "…";
    }
}
