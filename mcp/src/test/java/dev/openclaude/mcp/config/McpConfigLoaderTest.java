package dev.openclaude.mcp.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void urlOnlyConfig_defaultsToSseTransport() throws Exception {
        Path config = tempDir.resolve(".mcp.json");
        Files.writeString(config, """
                {"mcpServers": {
                  "remote": {"url": "https://internal.example.com/mcp"},
                  "local": {"command": "npx", "args": ["-y", "some-server"]}
                }}
                """);

        Map<String, McpServerConfig> configs = McpConfigLoader.loadFromFile(config);

        assertEquals("sse", configs.get("remote").type());
        assertEquals("https://internal.example.com/mcp", configs.get("remote").url());
        assertEquals("stdio", configs.get("local").type());
    }

    @Test
    void explicitType_isRespected() throws Exception {
        Path config = tempDir.resolve(".mcp.json");
        Files.writeString(config, """
                {"mcpServers": {
                  "remote": {"type": "http", "url": "https://example.com/mcp"}
                }}
                """);

        assertEquals("http", McpConfigLoader.loadFromFile(config).get("remote").type());
    }

    @Test
    void headerAndUrlValues_expandEnvVars() throws Exception {
        // HOME is guaranteed present on the platforms the build supports
        String home = System.getenv("HOME");
        Path config = tempDir.resolve(".mcp.json");
        Files.writeString(config, """
                {"mcpServers": {
                  "remote": {
                    "url": "https://example.com/${HOME}",
                    "headers": {"Authorization": "Bearer ${HOME}"}
                  }
                }}
                """);

        McpServerConfig remote = McpConfigLoader.loadFromFile(config).get("remote");

        assertEquals("https://example.com/" + home, remote.url());
        assertEquals("Bearer " + home, remote.headers().get("Authorization"));
    }
}
