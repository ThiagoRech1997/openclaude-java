package dev.openclaude.core.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads {@code CLAUDE.md} files and produces a system-prompt prefix that injects
 * per-project and per-user conventions into the agent.
 *
 * <p>Search order (all found files are concatenated, most general first):
 * <ol>
 *   <li>{@code ~/.claude/CLAUDE.md} — user-level memory</li>
 *   <li>{@code <cwd>/.claude/CLAUDE.md} — project-local memory</li>
 *   <li>{@code <cwd>/CLAUDE.md} — project instructions, checked into the codebase</li>
 * </ol>
 *
 * <p>Disabled when the env var {@code OPENCLAUDE_DISABLE_CLAUDE_MD} is truthy.
 */
public final class ClaudeMdLoader {

    private static final String DISABLE_ENV = "OPENCLAUDE_DISABLE_CLAUDE_MD";

    public record Source(Path path, String label, String content) {}

    public record Result(List<Source> sources) {
        public boolean isEmpty() { return sources.isEmpty(); }

        public String toSystemPromptPrefix() {
            if (sources.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            sb.append("# claudeMd\n")
              .append("Codebase and user instructions are shown below. Be sure to adhere to these instructions. ")
              .append("IMPORTANT: These instructions OVERRIDE any default behavior and you MUST follow them exactly as written.\n");
            for (int i = 0; i < sources.size(); i++) {
                Source s = sources.get(i);
                sb.append('\n')
                  .append("Contents of ").append(s.path()).append(" (").append(s.label()).append("):\n\n")
                  .append(s.content());
                if (!s.content().endsWith("\n")) sb.append('\n');
                if (i < sources.size() - 1) {
                    sb.append("\n---\n");
                }
            }
            return sb.toString();
        }
    }

    private ClaudeMdLoader() {}

    public static Result load(Path workingDirectory) {
        boolean disabled = isTrue(System.getenv(DISABLE_ENV));
        String home = System.getProperty("user.home");
        Path homeDir = (home != null && !home.isBlank()) ? Path.of(home) : null;
        return loadInternal(workingDirectory, homeDir, disabled);
    }

    static Result loadInternal(Path workingDirectory, Path homeDir, boolean disabled) {
        if (disabled) return new Result(List.of());

        List<Source> sources = new ArrayList<>();

        if (homeDir != null) {
            Path userLevel = homeDir.resolve(".claude").resolve("CLAUDE.md");
            readIfPresent(userLevel, "user-level memory", sources);
        }

        if (workingDirectory != null) {
            Path projectLocal = workingDirectory.resolve(".claude").resolve("CLAUDE.md");
            readIfPresent(projectLocal, "project-local memory", sources);

            Path projectRoot = workingDirectory.resolve("CLAUDE.md");
            readIfPresent(projectRoot, "project instructions, checked into the codebase", sources);
        }

        return new Result(List.copyOf(sources));
    }

    private static void readIfPresent(Path path, String label, List<Source> out) {
        if (path == null || !Files.exists(path) || !Files.isRegularFile(path)) return;
        try {
            String content = Files.readString(path);
            if (!content.isBlank()) {
                out.add(new Source(path.toAbsolutePath(), label, content));
            }
        } catch (IOException ignored) {
            // Silently skip unreadable files, following HooksConfigLoader pattern.
        }
    }

    private static boolean isTrue(String val) {
        return "1".equals(val) || "true".equalsIgnoreCase(val);
    }
}
