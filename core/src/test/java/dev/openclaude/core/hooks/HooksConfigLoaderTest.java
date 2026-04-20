package dev.openclaude.core.hooks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HooksConfigLoaderTest {

    @Test
    void loadFromFile_missing_returnsEmpty(@TempDir Path dir) {
        HookConfig cfg = HooksConfigLoader.loadFromFile(dir.resolve("nope.json"));
        assertTrue(cfg.isEmpty());
    }

    @Test
    void loadFromFile_noHooksKey_returnsEmpty(@TempDir Path dir) throws Exception {
        Path p = dir.resolve("settings.json");
        Files.writeString(p, "{ \"other\": {} }");
        assertTrue(HooksConfigLoader.loadFromFile(p).isEmpty());
    }

    @Test
    void loadFromFile_allThreeEvents_parsesMatchersAndCommands(@TempDir Path dir) throws Exception {
        Path p = dir.resolve("settings.json");
        Files.writeString(p, """
                {
                  "hooks": {
                    "PreToolUse": [
                      {
                        "matcher": "Bash|Edit",
                        "hooks": [
                          { "type": "command", "command": "echo pre", "timeout": 30 }
                        ]
                      }
                    ],
                    "PostToolUse": [
                      {
                        "hooks": [ { "type": "command", "command": "echo post" } ]
                      }
                    ],
                    "UserPromptSubmit": [
                      {
                        "matcher": "",
                        "hooks": [ { "type": "command", "command": "echo ups" } ]
                      }
                    ]
                  }
                }
                """);

        HookConfig cfg = HooksConfigLoader.loadFromFile(p);

        List<HookConfig.HookMatcher> pre = cfg.matchersFor(HookEvent.PRE_TOOL_USE);
        assertEquals(1, pre.size());
        assertNotNull(pre.get(0).matcher());
        assertTrue(pre.get(0).matches("Bash"));
        assertTrue(pre.get(0).matches("Edit"));
        assertFalse(pre.get(0).matches("Read"));
        assertEquals("echo pre", pre.get(0).hooks().get(0).command());
        assertEquals(30, pre.get(0).hooks().get(0).timeoutSeconds());

        List<HookConfig.HookMatcher> post = cfg.matchersFor(HookEvent.POST_TOOL_USE);
        assertEquals(1, post.size());
        assertNull(post.get(0).matcher(), "missing matcher → null pattern (match-all)");
        assertTrue(post.get(0).matches("anything"));
        assertEquals(HookConfig.DEFAULT_TIMEOUT_SECONDS, post.get(0).hooks().get(0).timeoutSeconds());

        List<HookConfig.HookMatcher> ups = cfg.matchersFor(HookEvent.USER_PROMPT_SUBMIT);
        assertEquals(1, ups.size());
        assertNull(ups.get(0).matcher(), "empty matcher string → null pattern (match-all)");
    }

    @Test
    void loadFromFile_unknownEvent_isIgnored(@TempDir Path dir) throws Exception {
        Path p = dir.resolve("settings.json");
        Files.writeString(p, """
                {
                  "hooks": {
                    "NotARealEvent": [ { "hooks": [ { "command": "x" } ] } ],
                    "PreToolUse": [ { "hooks": [ { "command": "x" } ] } ]
                  }
                }
                """);
        HookConfig cfg = HooksConfigLoader.loadFromFile(p);
        assertEquals(1, cfg.matchersFor(HookEvent.PRE_TOOL_USE).size());
        assertEquals(1, cfg.byEvent().size(), "only PreToolUse kept; unknown event dropped");
    }

    @Test
    void loadFromFile_invalidRegex_dropsMatcher(@TempDir Path dir) throws Exception {
        Path p = dir.resolve("settings.json");
        Files.writeString(p, """
                {
                  "hooks": {
                    "PreToolUse": [
                      { "matcher": "[unclosed", "hooks": [ { "command": "x" } ] },
                      { "matcher": "Bash", "hooks": [ { "command": "y" } ] }
                    ]
                  }
                }
                """);
        HookConfig cfg = HooksConfigLoader.loadFromFile(p);
        List<HookConfig.HookMatcher> pre = cfg.matchersFor(HookEvent.PRE_TOOL_USE);
        assertEquals(1, pre.size());
        assertEquals("y", pre.get(0).hooks().get(0).command());
    }

    @Test
    void loadFromFile_blankCommand_dropped(@TempDir Path dir) throws Exception {
        Path p = dir.resolve("settings.json");
        Files.writeString(p, """
                {
                  "hooks": {
                    "PreToolUse": [
                      { "hooks": [ { "command": "" }, { "command": "real" } ] }
                    ]
                  }
                }
                """);
        HookConfig cfg = HooksConfigLoader.loadFromFile(p);
        assertEquals(1, cfg.matchersFor(HookEvent.PRE_TOOL_USE).get(0).hooks().size());
    }

    @Test
    void load_projectSettingsMergesAfterUser(@TempDir Path cwd) throws Exception {
        Path claudeDir = cwd.resolve(".claude");
        Files.createDirectories(claudeDir);
        Files.writeString(claudeDir.resolve("settings.json"), """
                {
                  "hooks": {
                    "PreToolUse": [
                      { "matcher": "Edit", "hooks": [ { "command": "project-hook" } ] }
                    ]
                  }
                }
                """);

        HookConfig cfg = HooksConfigLoader.load(cwd);
        List<HookConfig.HookMatcher> pre = cfg.matchersFor(HookEvent.PRE_TOOL_USE);
        assertTrue(pre.stream().anyMatch(m ->
                m.hooks().stream().anyMatch(h -> "project-hook".equals(h.command()))));
    }
}
