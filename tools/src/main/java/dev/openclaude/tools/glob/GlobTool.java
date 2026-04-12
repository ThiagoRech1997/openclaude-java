package dev.openclaude.tools.glob;

import com.fasterxml.jackson.databind.JsonNode;
import dev.openclaude.tools.*;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Searches for files matching a glob pattern.
 */
public class GlobTool implements Tool {

    private static final int MAX_RESULTS = 500;

    private static final JsonNode SCHEMA = SchemaBuilder.object()
            .stringProp("pattern", "The glob pattern to match files (e.g., '**/*.java').", true)
            .stringProp("path", "Directory to search in. Defaults to working directory.", false)
            .build();

    @Override
    public String name() {
        return "Glob";
    }

    @Override
    public String description() {
        return "Fast file search by glob pattern. Returns matching file paths sorted by modification time.";
    }

    @Override
    public JsonNode inputSchema() {
        return SCHEMA;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(JsonNode input, ToolUseContext context) {
        String pattern = input.path("pattern").asText("");
        if (pattern.isBlank()) {
            return ToolResult.error("pattern is required.");
        }

        String pathStr = input.path("path").asText("");
        Path searchDir = pathStr.isBlank()
                ? context.workingDirectory()
                : Path.of(pathStr);

        if (!Files.isDirectory(searchDir)) {
            return ToolResult.error("Directory does not exist: " + searchDir);
        }

        try {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            List<Path> matches = new ArrayList<>();

            Files.walkFileTree(searchDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    Path relative = searchDir.relativize(file);
                    if (matcher.matches(relative)) {
                        matches.add(file);
                    }
                    if (matches.size() >= MAX_RESULTS) {
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dirName = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    if (dirName.equals(".git") || dirName.equals("node_modules") || dirName.equals("build")
                            || dirName.equals(".gradle") || dirName.equals("target")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });

            // Sort by modification time (newest first)
            matches.sort(Comparator.comparingLong((Path p) -> {
                try {
                    return Files.getLastModifiedTime(p).toMillis();
                } catch (IOException e) {
                    return 0;
                }
            }).reversed());

            if (matches.isEmpty()) {
                return ToolResult.success("No files found matching pattern: " + pattern);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Found ").append(matches.size()).append(" file(s):\n");
            for (Path match : matches) {
                sb.append(match).append('\n');
            }
            if (matches.size() >= MAX_RESULTS) {
                sb.append("\n(results truncated at ").append(MAX_RESULTS).append(")\n");
            }

            return ToolResult.success(sb.toString());

        } catch (IOException e) {
            return ToolResult.error("Glob search failed: " + e.getMessage());
        }
    }
}
