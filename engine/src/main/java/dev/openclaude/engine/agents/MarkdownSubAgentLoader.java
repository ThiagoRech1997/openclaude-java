package dev.openclaude.engine.agents;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Loads sub-agent definitions from markdown files.
 *
 * <p>Scans two directories (non-recursive):
 * <ul>
 *   <li>{@code ~/.claude/agents/*.md} — tagged source="user"
 *   <li>{@code <cwd>/.claude/agents/*.md} — tagged source="project"
 * </ul>
 *
 * <p>User entries are loaded first, project entries last. Callers register in order,
 * so project > user > built-in when names collide.
 *
 * <p>Frontmatter keys:
 * <ul>
 *   <li>{@code name}: agent name (defaults to filename without {@code .md}; case is preserved
 *       so a user file {@code Explore.md} can override the built-in {@code Explore}).
 *   <li>{@code description}: short description.
 *   <li>{@code tools}: inline list, e.g. {@code [Read, Grep]}. Block lists
 *       ({@code - Read\n- Grep}) are NOT supported (matches {@code CustomCommandLoader}).
 *   <li>{@code model}: optional alias ({@code sonnet}/{@code opus}/{@code haiku}) or full ID.
 * </ul>
 */
public final class MarkdownSubAgentLoader {

    public List<SubAgentDefinition> load(Path cwd) {
        return loadInternal(cwd, userHomeAgentsDir());
    }

    /** Test seam: inject the user-home agents dir explicitly. */
    List<SubAgentDefinition> loadInternal(Path cwd, Path userAgentsDir) {
        List<SubAgentDefinition> result = new ArrayList<>();
        if (userAgentsDir != null) result.addAll(scanDirectory(userAgentsDir, "user"));
        if (cwd != null) {
            Path projectDir = cwd.resolve(".claude").resolve("agents");
            result.addAll(scanDirectory(projectDir, "project"));
        }
        return result;
    }

    private static Path userHomeAgentsDir() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) return null;
        return Path.of(home, ".claude", "agents");
    }

    private static List<SubAgentDefinition> scanDirectory(Path dir, String source) {
        List<SubAgentDefinition> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".md"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .forEach(p -> {
                        SubAgentDefinition def = parseFile(p, source);
                        if (def != null) out.add(def);
                    });
        } catch (IOException e) {
            System.err.println("[sub-agents] failed to list " + dir + ": " + e.getMessage());
        }
        return out;
    }

    static SubAgentDefinition parseFile(Path file, String source) {
        String raw;
        try {
            raw = Files.readString(file);
        } catch (IOException e) {
            System.err.println("[sub-agents] failed to read " + file + ": " + e.getMessage());
            return null;
        }
        String filename = file.getFileName().toString();
        String defaultName = filename.substring(0, filename.length() - 3);
        return parseContent(defaultName, raw, source);
    }

    static SubAgentDefinition parseContent(String defaultName, String raw, String source) {
        String name = defaultName;
        String description = "(custom sub-agent)";
        List<String> toolWhitelist = null;
        String modelOverride = null;
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
                    if (fm.name != null && !fm.name.isBlank()) name = fm.name;
                    if (fm.description != null) description = fm.description;
                    if (fm.tools != null && !fm.tools.isEmpty()) toolWhitelist = fm.tools;
                    modelOverride = fm.model;
                }
            }
        }

        String systemPrompt = body.trim();
        if (systemPrompt.isEmpty()) {
            System.err.println("[sub-agents] skipping " + defaultName + ": empty body");
            return null;
        }
        return new SubAgentDefinition(name, description, systemPrompt, toolWhitelist, null, modelOverride, source);
    }

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
                case "name" -> fm.name = value;
                case "description" -> fm.description = value;
                case "tools" -> fm.tools = parseList(value);
                case "model" -> fm.model = value;
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
        String name;
        String description;
        List<String> tools;
        String model;
    }
}
