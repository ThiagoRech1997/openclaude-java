package dev.openclaude.engine.agents;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownSubAgentLoaderTest {

    @Test
    void load_noDirectories_returnsEmpty(@TempDir Path cwd, @TempDir Path home) {
        MarkdownSubAgentLoader loader = new MarkdownSubAgentLoader();
        List<SubAgentDefinition> result = loader.loadInternal(cwd, home.resolve("nope"));
        assertTrue(result.isEmpty());
    }

    @Test
    void load_parsesFrontmatterFields(@TempDir Path cwd, @TempDir Path home) throws Exception {
        Path projectDir = cwd.resolve(".claude").resolve("agents");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("code-reviewer.md"), """
                ---
                name: code-reviewer
                description: Reviews code
                tools: [FileRead, Grep]
                model: sonnet
                ---
                You are a careful code reviewer.
                """);

        List<SubAgentDefinition> result = new MarkdownSubAgentLoader().loadInternal(cwd, home);

        assertEquals(1, result.size());
        SubAgentDefinition def = result.get(0);
        assertEquals("code-reviewer", def.name());
        assertEquals("Reviews code", def.description());
        assertEquals(List.of("FileRead", "Grep"), def.toolWhitelist());
        assertEquals("sonnet", def.modelOverride());
        assertEquals("project", def.source());
        assertTrue(def.systemPrompt().contains("careful code reviewer"));
        assertNull(def.toolFilter());
    }

    @Test
    void load_fileWithoutFrontmatter_usesFilenameAsName(@TempDir Path cwd, @TempDir Path home) throws Exception {
        Path projectDir = cwd.resolve(".claude").resolve("agents");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("greeter.md"), "You greet people.\n");

        List<SubAgentDefinition> result = new MarkdownSubAgentLoader().loadInternal(cwd, home);

        assertEquals(1, result.size());
        SubAgentDefinition def = result.get(0);
        assertEquals("greeter", def.name());
        assertEquals("(custom sub-agent)", def.description());
        assertNull(def.toolWhitelist());
        assertNull(def.modelOverride());
        assertEquals("You greet people.", def.systemPrompt());
    }

    @Test
    void load_frontmatterNameOverridesFilename(@TempDir Path cwd, @TempDir Path home) throws Exception {
        Path projectDir = cwd.resolve(".claude").resolve("agents");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("greeter.md"), """
                ---
                name: fancy-greeter
                ---
                Hello.
                """);

        List<SubAgentDefinition> result = new MarkdownSubAgentLoader().loadInternal(cwd, home);

        assertEquals(1, result.size());
        assertEquals("fancy-greeter", result.get(0).name());
    }

    @Test
    void load_filenamePreservesCase(@TempDir Path cwd, @TempDir Path home) throws Exception {
        // Case preservation lets a user file shadow the built-in "Explore" by name.
        Path projectDir = cwd.resolve(".claude").resolve("agents");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("Explore.md"), "Custom explorer.\n");

        List<SubAgentDefinition> result = new MarkdownSubAgentLoader().loadInternal(cwd, home);

        assertEquals(1, result.size());
        assertEquals("Explore", result.get(0).name());
    }

    @Test
    void load_projectAfterUser_ordering(@TempDir Path cwd, @TempDir Path homeAgentsDir) throws Exception {
        Files.createDirectories(homeAgentsDir);
        Files.writeString(homeAgentsDir.resolve("same.md"), """
                ---
                description: from user
                ---
                user body
                """);

        Path projectDir = cwd.resolve(".claude").resolve("agents");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("same.md"), """
                ---
                description: from project
                ---
                project body
                """);

        List<SubAgentDefinition> result = new MarkdownSubAgentLoader().loadInternal(cwd, homeAgentsDir);

        assertEquals(2, result.size());
        assertEquals("user", result.get(0).source());
        assertEquals("project", result.get(1).source());
    }

    @Test
    void load_toolsListInlineArray(@TempDir Path cwd, @TempDir Path home) throws Exception {
        Path projectDir = cwd.resolve(".claude").resolve("agents");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("a.md"), """
                ---
                tools: [FileRead, "WebFetch"]
                ---
                body
                """);

        List<SubAgentDefinition> result = new MarkdownSubAgentLoader().loadInternal(cwd, home);

        assertEquals(List.of("FileRead", "WebFetch"), result.get(0).toolWhitelist());
    }

    @Test
    void load_toolsEmpty_isInheritNotDenyAll(@TempDir Path cwd, @TempDir Path home) throws Exception {
        Path projectDir = cwd.resolve(".claude").resolve("agents");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("a.md"), """
                ---
                tools: []
                ---
                body
                """);

        List<SubAgentDefinition> result = new MarkdownSubAgentLoader().loadInternal(cwd, home);

        assertNull(result.get(0).toolWhitelist(), "empty tools list must fall back to inherit, not deny-all");
    }

    @Test
    void load_emptyBody_isSkipped(@TempDir Path cwd, @TempDir Path home) throws Exception {
        Path projectDir = cwd.resolve(".claude").resolve("agents");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("empty.md"), """
                ---
                description: No body
                ---
                """);

        List<SubAgentDefinition> result = new MarkdownSubAgentLoader().loadInternal(cwd, home);

        assertTrue(result.isEmpty(), "empty-body agent files must not produce a registration");
    }

    @Test
    void load_unknownFrontmatterKeys_ignored(@TempDir Path cwd, @TempDir Path home) throws Exception {
        Path projectDir = cwd.resolve(".claude").resolve("agents");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("a.md"), """
                ---
                description: valid
                weird-key: ignore-me
                ---
                body
                """);

        List<SubAgentDefinition> result = new MarkdownSubAgentLoader().loadInternal(cwd, home);

        assertEquals(1, result.size());
        assertEquals("valid", result.get(0).description());
    }
}
