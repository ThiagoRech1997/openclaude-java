package dev.openclaude.commands.loader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Loads custom slash commands from markdown files.
 *
 * <p>Scans two directories (non-recursive):
 * <ul>
 *   <li>{@code <cwd>/.claude/commands/*.md} — tagged source="project"
 *   <li>{@code ~/.claude/commands/*.md} — tagged source="user"
 * </ul>
 *
 * <p>Precedence: project-local overrides user-global when the same command name appears in both.
 * (User commands are loaded first, project commands last, so the later ones end up at the tail
 * of the returned list — the caller registers them in order, letting the project entry take over.)
 */
public final class CustomCommandLoader {

    public List<MarkdownCommand> load(Path cwd) {
        return loadInternal(cwd, userHomeCommandsDir());
    }

    /** Test seam: inject the user-home commands dir explicitly. */
    List<MarkdownCommand> loadInternal(Path cwd, Path userCommandsDir) {
        List<MarkdownCommand> result = new ArrayList<>();

        if (userCommandsDir != null) {
            result.addAll(scanDirectory(userCommandsDir, "user"));
        }

        if (cwd != null) {
            Path projectDir = cwd.resolve(".claude").resolve("commands");
            result.addAll(scanDirectory(projectDir, "project"));
        }

        return result;
    }

    private static Path userHomeCommandsDir() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) return null;
        return Path.of(home, ".claude", "commands");
    }

    private static List<MarkdownCommand> scanDirectory(Path dir, String source) {
        List<MarkdownCommand> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;

        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".md"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .forEach(p -> {
                        MarkdownCommand cmd = parseFile(p, source);
                        if (cmd != null) out.add(cmd);
                    });
        } catch (IOException e) {
            System.err.println("[custom-commands] failed to list " + dir + ": " + e.getMessage());
        }
        return out;
    }

    static MarkdownCommand parseFile(Path file, String source) {
        String raw;
        try {
            raw = Files.readString(file);
        } catch (IOException e) {
            System.err.println("[custom-commands] failed to read " + file + ": " + e.getMessage());
            return null;
        }

        String filename = file.getFileName().toString();
        String name = filename.substring(0, filename.length() - 3).toLowerCase();

        return parseContent(name, raw, source);
    }

    static MarkdownCommand parseContent(String name, String raw, String source) {
        String description = "(custom command)";
        String argumentHint = null;
        List<String> allowedTools = List.of();
        String body = raw;

        if (raw.startsWith("---")) {
            int firstNewline = raw.indexOf('\n');
            if (firstNewline >= 0) {
                int end = findFrontmatterEnd(raw, firstNewline + 1);
                if (end >= 0) {
                    String frontmatter = raw.substring(firstNewline + 1, end);
                    int bodyStart = raw.indexOf('\n', end);
                    body = bodyStart >= 0 ? raw.substring(bodyStart + 1) : "";

                    Frontmatter fm = parseFrontmatter(frontmatter);
                    if (fm.description != null) description = fm.description;
                    argumentHint = fm.argumentHint;
                    allowedTools = fm.allowedTools;
                }
                // malformed frontmatter (no closing ---): fall through, whole file is body
            }
        }

        return new MarkdownCommand(name, description, argumentHint, allowedTools, body, source);
    }

    /** Returns the index of the closing {@code ---} marker line, or -1 if not found. */
    private static int findFrontmatterEnd(String raw, int searchFrom) {
        int i = searchFrom;
        while (i < raw.length()) {
            int lineEnd = raw.indexOf('\n', i);
            String line = (lineEnd < 0) ? raw.substring(i) : raw.substring(i, lineEnd);
            if (line.trim().equals("---")) return i;
            if (lineEnd < 0) return -1;
            i = lineEnd + 1;
        }
        return -1;
    }

    private static Frontmatter parseFrontmatter(String text) {
        Frontmatter fm = new Frontmatter();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int colon = trimmed.indexOf(':');
            if (colon <= 0) continue;

            String key = trimmed.substring(0, colon).trim().toLowerCase();
            String value = stripQuotes(trimmed.substring(colon + 1).trim());

            switch (key) {
                case "description" -> fm.description = value;
                case "argument-hint" -> fm.argumentHint = value;
                case "allowed-tools" -> fm.allowedTools = parseList(value);
                default -> { /* ignore unknown keys */ }
            }
        }
        return fm;
    }

    private static List<String> parseList(String value) {
        if (value.isEmpty()) return List.of();
        String inner = value;
        if (inner.startsWith("[") && inner.endsWith("]")) {
            inner = inner.substring(1, inner.length() - 1);
        }
        List<String> out = new ArrayList<>();
        for (String part : inner.split(",")) {
            String clean = stripQuotes(part.trim());
            if (!clean.isEmpty()) out.add(clean);
        }
        return List.copyOf(out);
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    private static final class Frontmatter {
        String description;
        String argumentHint;
        List<String> allowedTools = List.of();
    }
}
