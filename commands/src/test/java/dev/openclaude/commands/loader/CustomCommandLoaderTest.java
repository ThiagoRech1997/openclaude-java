package dev.openclaude.commands.loader;

import dev.openclaude.commands.CommandRegistry;
import dev.openclaude.commands.CommandRegistryFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CustomCommandLoaderTest {

    @Test
    void load_noDirectories_returnsEmpty(@TempDir Path cwd, @TempDir Path home) {
        CustomCommandLoader loader = new CustomCommandLoader();
        List<MarkdownCommand> result = loader.loadInternal(cwd, home.resolve("nope"));
        assertTrue(result.isEmpty());
    }

    @Test
    void load_parsesFrontmatterFields(@TempDir Path cwd, @TempDir Path home) throws Exception {
        Path projectDir = cwd.resolve(".claude").resolve("commands");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("deploy.md"), """
                ---
                description: Deploy the app
                argument-hint: <env>
                allowed-tools: Bash, WebFetch
                ---
                Run deploy to $ARGUMENTS now.
                """);

        List<MarkdownCommand> result = new CustomCommandLoader().loadInternal(cwd, home);

        assertEquals(1, result.size());
        MarkdownCommand cmd = result.get(0);
        assertEquals("deploy", cmd.name());
        assertEquals("Deploy the app", cmd.descriptionText());
        assertEquals("<env>", cmd.argumentHint());
        assertEquals(List.of("Bash", "WebFetch"), cmd.allowedTools());
        assertEquals("project", cmd.source());
        assertTrue(cmd.body().contains("Run deploy to $ARGUMENTS now."));
    }

    @Test
    void load_fileWithoutFrontmatter_usesDefaultDescription(@TempDir Path cwd, @TempDir Path home) throws Exception {
        Path projectDir = cwd.resolve(".claude").resolve("commands");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("plain.md"), "Just say hi.\n");

        List<MarkdownCommand> result = new CustomCommandLoader().loadInternal(cwd, home);

        assertEquals(1, result.size());
        MarkdownCommand cmd = result.get(0);
        assertEquals("plain", cmd.name());
        assertEquals("(custom command)", cmd.descriptionText());
        assertNull(cmd.argumentHint());
        assertEquals("Just say hi.\n", cmd.body());
    }

    @Test
    void load_malformedFrontmatter_treatsWholeFileAsBody(@TempDir Path cwd, @TempDir Path home) throws Exception {
        Path projectDir = cwd.resolve(".claude").resolve("commands");
        Files.createDirectories(projectDir);
        // Missing closing ---
        Files.writeString(projectDir.resolve("broken.md"), """
                ---
                description: Never closes
                body content without a closing marker
                """);

        List<MarkdownCommand> result = new CustomCommandLoader().loadInternal(cwd, home);

        assertEquals(1, result.size());
        assertEquals("(custom command)", result.get(0).descriptionText());
    }

    @Test
    void load_unknownFrontmatterKeys_areIgnored(@TempDir Path cwd, @TempDir Path home) throws Exception {
        Path projectDir = cwd.resolve(".claude").resolve("commands");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("weird.md"), """
                ---
                description: Has extras
                unknown-key: ignore-me
                another: also-ignored
                ---
                body
                """);

        List<MarkdownCommand> result = new CustomCommandLoader().loadInternal(cwd, home);

        assertEquals(1, result.size());
        assertEquals("Has extras", result.get(0).descriptionText());
    }

    @Test
    void load_allowedToolsArraySyntax(@TempDir Path cwd, @TempDir Path home) throws Exception {
        Path projectDir = cwd.resolve(".claude").resolve("commands");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("tools.md"), """
                ---
                description: d
                allowed-tools: [Bash, "WebFetch"]
                ---
                body
                """);

        List<MarkdownCommand> result = new CustomCommandLoader().loadInternal(cwd, home);

        assertEquals(List.of("Bash", "WebFetch"), result.get(0).allowedTools());
    }

    @Test
    void load_projectWinsOverUser(@TempDir Path cwd, @TempDir Path homeCommandsDir) throws Exception {
        Files.createDirectories(homeCommandsDir);
        Files.writeString(homeCommandsDir.resolve("same.md"), """
                ---
                description: from user
                ---
                user body
                """);

        Path projectDir = cwd.resolve(".claude").resolve("commands");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("same.md"), """
                ---
                description: from project
                ---
                project body
                """);

        List<MarkdownCommand> result = new CustomCommandLoader().loadInternal(cwd, homeCommandsDir);

        // Both entries returned; project is last so it overrides when registered in order.
        assertEquals(2, result.size());
        assertEquals("user", result.get(0).source());
        assertEquals("project", result.get(1).source());

        CommandRegistry registry = new CommandRegistry();
        registry.register(result.get(0));
        registry.register(result.get(1));
        MarkdownCommand resolved = (MarkdownCommand) registry.find("same").orElseThrow();
        assertEquals("project", resolved.source());
    }

    @Test
    void factory_projectOverridesUser_whenBothExist(@TempDir Path cwd, @TempDir Path fakeHome) throws Exception {
        Path userCommands = fakeHome.resolve(".claude").resolve("commands");
        Files.createDirectories(userCommands);
        Files.writeString(userCommands.resolve("greet.md"), """
                ---
                description: from user
                ---
                user body
                """);

        Path projectCommands = cwd.resolve(".claude").resolve("commands");
        Files.createDirectories(projectCommands);
        Files.writeString(projectCommands.resolve("greet.md"), """
                ---
                description: from project
                ---
                project body
                """);

        String originalHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());
        try {
            CommandRegistry registry = new CommandRegistryFactory().create(cwd);
            MarkdownCommand resolved = (MarkdownCommand) registry.find("greet").orElseThrow();
            assertEquals("project", resolved.source(),
                    "project-local custom command must shadow user-global one with the same name");
            assertTrue(resolved.body().contains("project body"));
        } finally {
            if (originalHome != null) System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void factory_skipsCollisionsWithBuiltins(@TempDir Path cwd, @TempDir Path home) throws Exception {
        Path projectDir = cwd.resolve(".claude").resolve("commands");
        Files.createDirectories(projectDir);
        // "help" is a built-in — the loader's output still includes it,
        // but CommandRegistryFactory.create(Path) must refuse to overwrite.
        Files.writeString(projectDir.resolve("help.md"), """
                ---
                description: evil override
                ---
                override attempt
                """);

        // Pass cwd so factory scans the temp project dir. We can't inject our fake home
        // into the factory, but for this check the built-in collision is what matters.
        CommandRegistry registry = new CommandRegistryFactory().create(cwd);

        // The built-in /help must still win.
        var helpCmd = registry.find("help").orElseThrow();
        assertNotEquals("evil override", helpCmd.description(),
                "built-in /help must not be overwritten by a project markdown file");
    }
}
