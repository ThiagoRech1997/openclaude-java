package dev.openclaude.commands.impl;

import com.fasterxml.jackson.databind.JsonNode;
import dev.openclaude.commands.*;
import dev.openclaude.tools.Tool;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class DocsCommand implements Command {
    @Override public String name() { return "docs"; }
    @Override public String description() { return "Generate complete technical documentation"; }
    @Override public String[] aliases() { return new String[]{"documentation"}; }

    @Override
    public CommandResult execute(String args, CommandContext ctx) {
        StringBuilder sb = new StringBuilder();

        header(sb);
        environmentSection(sb, ctx);
        architectureSection(sb);
        toolsSection(sb, ctx);
        commandsSection(sb, ctx);
        configSection(sb);

        return CommandResult.text(sb.toString());
    }

    private void header(StringBuilder sb) {
        sb.append("\n");
        sb.append("  ╔══════════════════════════════════════════╗\n");
        sb.append("  ║     OpenClaude Java — Technical Docs     ║\n");
        sb.append("  ╚══════════════════════════════════════════╝\n");
    }

    private void environmentSection(StringBuilder sb, CommandContext ctx) {
        sb.append("\n  ## Environment\n\n");
        sb.append(String.format("    Provider:      %s%n", ctx.config().provider()));
        sb.append(String.format("    Model:         %s%n", ctx.config().model()));
        sb.append(String.format("    Max Tokens:    %d%n", ctx.config().maxTokens()));
        sb.append(String.format("    Directory:     %s%n", ctx.workingDirectory()));
        sb.append(String.format("    Permission:    %s%n", ctx.permissions().getMode()));
        sb.append(String.format("    Java:          %s%n", System.getProperty("java.version")));
    }

    private void architectureSection(StringBuilder sb) {
        sb.append("\n  ## Architecture (10 modules)\n\n");
        sb.append("    cli → tui → engine → tools → core\n");
        sb.append("                     ↘ llm → core\n");
        sb.append("                     ↘ mcp → core\n");
        sb.append("                     ↘ commands → core\n");
        sb.append("          grpc → engine\n");
        sb.append("          plugins → tools\n");
        sb.append("\n");
        sb.append("    core       Sealed data models, config, permissions, sessions\n");
        sb.append("    llm        LlmClient implementations (Anthropic, OpenAI, Ollama)\n");
        sb.append("    tools      Tool interface, registry, built-in tools\n");
        sb.append("    engine     Agent loop (QueryEngine), context compaction, sub-agents\n");
        sb.append("    mcp        MCP client, tool bridge (stdio transport)\n");
        sb.append("    commands   REPL slash commands via CommandRegistry\n");
        sb.append("    plugins    Plugin discovery (ServiceLoader + JAR scanning)\n");
        sb.append("    grpc       Headless JSON-over-TCP server (port 9818)\n");
        sb.append("    tui        Terminal UI with JLine 3 (REPL, display, input)\n");
        sb.append("    cli        PicoCLI entry point, module wiring\n");
    }

    private void toolsSection(StringBuilder sb, CommandContext ctx) {
        var tools = ctx.toolRegistry().allTools();
        sb.append("\n  ## Tools (").append(tools.size()).append(" available)\n");

        List<Tool> builtIn = new ArrayList<>();
        List<Tool> mcpTools = new ArrayList<>();

        for (Tool tool : tools) {
            if (tool.name().startsWith("mcp__")) {
                mcpTools.add(tool);
            } else {
                builtIn.add(tool);
            }
        }

        if (!builtIn.isEmpty()) {
            sb.append("\n  ### Built-in Tools\n");
            for (Tool tool : builtIn) {
                renderTool(sb, tool, null);
            }
        }

        if (!mcpTools.isEmpty()) {
            sb.append("\n  ### MCP Tools\n");
            for (Tool tool : mcpTools) {
                String serverName = extractMcpServer(tool.name());
                renderTool(sb, tool, serverName);
            }
        }
    }

    private void renderTool(StringBuilder sb, Tool tool, String mcpServer) {
        sb.append("\n    ── ").append(tool.name());
        if (mcpServer != null) {
            sb.append(" [MCP: ").append(mcpServer).append("]");
        }
        if (tool.isReadOnly()) {
            sb.append(" (read-only)");
        }
        sb.append("\n");
        sb.append("       ").append(tool.description()).append("\n");

        JsonNode schema = tool.inputSchema();
        if (schema != null && schema.has("properties")) {
            sb.append("\n       Parameters:\n");
            JsonNode properties = schema.get("properties");
            List<String> required = new ArrayList<>();
            if (schema.has("required")) {
                for (JsonNode r : schema.get("required")) {
                    required.add(r.asText());
                }
            }

            Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String paramName = entry.getKey();
                JsonNode prop = entry.getValue();

                String type = prop.has("type") ? prop.get("type").asText() : "any";
                boolean isRequired = required.contains(paramName);
                String desc = prop.has("description") ? prop.get("description").asText() : "";

                sb.append(String.format("         %-20s (%s, %s)",
                        paramName, type, isRequired ? "required" : "optional"));
                if (!desc.isEmpty()) {
                    String shortDesc = desc.length() > 80 ? desc.substring(0, 77) + "..." : desc;
                    sb.append(" — ").append(shortDesc);
                }
                sb.append("\n");
            }
        }
    }

    private String extractMcpServer(String toolName) {
        String[] parts = toolName.split("__", 3);
        return parts.length >= 2 ? parts[1] : "unknown";
    }

    private void commandsSection(StringBuilder sb, CommandContext ctx) {
        if (ctx.commandRegistry() == null) return;

        var commands = ctx.commandRegistry().allCommands();
        sb.append("\n  ## Commands (").append(commands.size()).append(" available)\n\n");

        for (Command cmd : commands) {
            String aliases = "";
            if (cmd.aliases().length > 0) {
                aliases = " (" + String.join(", ", cmd.aliases()) + ")";
            }
            sb.append(String.format("    /%-14s%s — %s%n",
                    cmd.name(), aliases, cmd.description()));
        }
    }

    private void configSection(StringBuilder sb) {
        sb.append("\n  ## Configuration (Environment Variables)\n\n");
        sb.append("    ANTHROPIC_API_KEY        Anthropic API key (default provider)\n");
        sb.append("    OPENAI_API_KEY           OpenAI API key (+ CLAUDE_CODE_USE_OPENAI=1)\n");
        sb.append("    OLLAMA_BASE_URL          Ollama endpoint (default: http://localhost:11434)\n");
        sb.append("    OPENROUTER_API_KEY       OpenRouter API key\n");
        sb.append("    GITHUB_TOKEN             GitHub Models (+ CLAUDE_CODE_USE_GITHUB=1)\n");
        sb.append("    ANTHROPIC_BASE_URL       Override Anthropic API endpoint\n");
        sb.append("    OPENAI_BASE_URL          Override OpenAI API endpoint\n");
        sb.append("    OPENCLAUDE_MODEL         Override default model\n");
        sb.append("    OPENCLAUDE_MAX_TOKENS    Max response tokens (default: 16384)\n");
        sb.append("\n");
    }
}
