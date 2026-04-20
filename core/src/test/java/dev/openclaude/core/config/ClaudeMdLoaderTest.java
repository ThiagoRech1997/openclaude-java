package dev.openclaude.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ClaudeMdLoaderTest {

    @Test
    void load_noFiles_returnsEmpty(@TempDir Path home, @TempDir Path cwd) {
        var result = ClaudeMdLoader.loadInternal(cwd, home, false);
        assertTrue(result.isEmpty());
        assertEquals("", result.toSystemPromptPrefix());
    }

    @Test
    void load_onlyProjectRoot_singleSourceInPrefix(@TempDir Path home, @TempDir Path cwd) throws Exception {
        Path projectRoot = cwd.resolve("CLAUDE.md");
        Files.writeString(projectRoot, "Always use tabs for indentation.\n");

        var result = ClaudeMdLoader.loadInternal(cwd, home, false);

        assertEquals(1, result.sources().size());
        assertEquals("project instructions, checked into the codebase", result.sources().get(0).label());
        String prefix = result.toSystemPromptPrefix();
        assertTrue(prefix.startsWith("# claudeMd"));
        assertTrue(prefix.contains("Always use tabs for indentation."));
        assertTrue(prefix.contains(projectRoot.toAbsolutePath().toString()));
        assertFalse(prefix.contains("---"), "single source should not render a separator");
    }

    @Test
    void load_allThreeSources_orderedAndSeparated(@TempDir Path home, @TempDir Path cwd) throws Exception {
        Path userLevel = home.resolve(".claude").resolve("CLAUDE.md");
        Files.createDirectories(userLevel.getParent());
        Files.writeString(userLevel, "USER_CONTENT");

        Path projectLocal = cwd.resolve(".claude").resolve("CLAUDE.md");
        Files.createDirectories(projectLocal.getParent());
        Files.writeString(projectLocal, "PROJECT_LOCAL_CONTENT");

        Path projectRoot = cwd.resolve("CLAUDE.md");
        Files.writeString(projectRoot, "PROJECT_ROOT_CONTENT");

        var result = ClaudeMdLoader.loadInternal(cwd, home, false);

        assertEquals(3, result.sources().size());
        assertEquals("user-level memory", result.sources().get(0).label());
        assertEquals("project-local memory", result.sources().get(1).label());
        assertEquals("project instructions, checked into the codebase", result.sources().get(2).label());

        String prefix = result.toSystemPromptPrefix();
        int userIdx = prefix.indexOf("USER_CONTENT");
        int localIdx = prefix.indexOf("PROJECT_LOCAL_CONTENT");
        int rootIdx = prefix.indexOf("PROJECT_ROOT_CONTENT");
        assertTrue(userIdx > 0 && userIdx < localIdx && localIdx < rootIdx,
                "sources must appear in order user → project-local → project-root");
        assertEquals(2, countOccurrences(prefix, "\n---\n"),
                "three sources should be joined by two separators");
    }

    @Test
    void load_disabled_returnsEmptyEvenIfFilesExist(@TempDir Path home, @TempDir Path cwd) throws Exception {
        Files.writeString(cwd.resolve("CLAUDE.md"), "ignored content");
        var result = ClaudeMdLoader.loadInternal(cwd, home, true);
        assertTrue(result.isEmpty());
    }

    @Test
    void load_nullHomeDir_doesNotThrow(@TempDir Path cwd) throws Exception {
        Files.writeString(cwd.resolve("CLAUDE.md"), "ok");
        var result = ClaudeMdLoader.loadInternal(cwd, null, false);
        assertEquals(1, result.sources().size());
    }

    @Test
    void load_blankFile_skipped(@TempDir Path home, @TempDir Path cwd) throws Exception {
        Files.writeString(cwd.resolve("CLAUDE.md"), "   \n\n");
        var result = ClaudeMdLoader.loadInternal(cwd, home, false);
        assertTrue(result.isEmpty());
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
