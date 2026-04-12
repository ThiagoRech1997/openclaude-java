package dev.openclaude.cli;

import dev.openclaude.core.config.AppConfig;
import dev.openclaude.core.model.*;
import dev.openclaude.engine.EngineEvent;
import dev.openclaude.engine.QueryEngine;
import dev.openclaude.llm.LlmClient;
import dev.openclaude.llm.provider.LlmClientFactory;
import dev.openclaude.tools.ToolRegistry;
import dev.openclaude.tools.bash.BashTool;
import dev.openclaude.tools.fileedit.FileEditTool;
import dev.openclaude.tools.fileread.FileReadTool;
import dev.openclaude.tools.filewrite.FileWriteTool;
import dev.openclaude.tools.glob.GlobTool;
import dev.openclaude.tools.grep.GrepTool;
import dev.openclaude.mcp.McpClientManager;
import dev.openclaude.mcp.McpToolBridge;
import dev.openclaude.mcp.config.McpConfigLoader;
import dev.openclaude.mcp.config.McpServerConfig;
import dev.openclaude.tui.Ansi;
import dev.openclaude.tui.Repl;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
        name = "openclaude",
        mixinStandardHelpOptions = true,
        version = "openclaude-java 0.1.0",
        description = "Open-source coding agent CLI for multiple LLM providers."
)
public class Main implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", description = "Initial prompt (omit for interactive REPL).")
    private String prompt;

    @Option(names = {"-m", "--model"}, description = "Model to use.")
    private String model;

    @Option(names = {"--system"}, description = "System prompt.",
            defaultValue = "You are a helpful coding assistant. You have access to tools for reading, writing, and editing files, running bash commands, and searching code. Use them when appropriate to help the user.")
    private String systemPrompt;

    @Option(names = {"-p", "--print"}, description = "Print mode: single prompt, no REPL.")
    private boolean printMode;

    @Override
    public Integer call() {
        AppConfig config = AppConfig.load();

        if (model != null) {
            config = new AppConfig(config.apiKey(), model, config.baseUrl(), config.provider(), config.maxTokens());
        }

        // No prompt and no print mode = interactive REPL
        if (prompt == null && !printMode) {
            config.validate();
            LlmClient client = LlmClientFactory.create(config);
            ToolRegistry tools = createToolRegistry();
            Path cwd = Path.of(System.getProperty("user.dir"));

            Repl repl = new Repl(config, client, tools, cwd, systemPrompt);
            repl.start();
            return 0;
        }

        // Print mode or single prompt
        if (prompt == null || prompt.isBlank()) {
            System.out.println("openclaude-java v0.1.0");
            System.out.println("Usage: openclaude [prompt]       Interactive REPL (or single prompt)");
            System.out.println("       openclaude -p \"prompt\"    Print mode (non-interactive)");
            return 0;
        }

        config.validate();

        LlmClient client = LlmClientFactory.create(config);
        ToolRegistry tools = createToolRegistry();
        Path cwd = Path.of(System.getProperty("user.dir"));

        System.out.println(Ansi.DIM + config.provider() + " / " + config.model()
                + " | " + tools.size() + " tools" + Ansi.RESET);
        System.out.println();

        QueryEngine engine = new QueryEngine(
                client, tools, config.model(), systemPrompt,
                config.maxTokens(), cwd, this::handlePrintEvent
        );

        engine.run(prompt);

        return 0;
    }

    private void handlePrintEvent(EngineEvent event) {
        if (event instanceof EngineEvent.Stream s) {
            StreamEvent se = s.event();
            if (se instanceof StreamEvent.TextDelta td) {
                System.out.print(td.text());
                System.out.flush();
            } else if (se instanceof StreamEvent.ThinkingDelta th) {
                System.out.print(Ansi.DIM + th.thinking() + Ansi.RESET);
                System.out.flush();
            } else if (se instanceof StreamEvent.Error err) {
                System.err.println(Ansi.RED + "Error: " + err.message() + Ansi.RESET);
            }
        } else if (event instanceof EngineEvent.ToolExecutionStart tes) {
            System.out.println();
            System.out.print(Ansi.CYAN + "  ⚡ " + tes.toolName() + Ansi.RESET + " ");
            System.out.flush();
        } else if (event instanceof EngineEvent.ToolExecutionEnd tee) {
            if (tee.result().isError()) {
                System.out.println(Ansi.RED + "✗" + Ansi.RESET);
            } else {
                System.out.println(Ansi.DIM + "✓" + Ansi.RESET);
            }
        } else if (event instanceof EngineEvent.Done done) {
            System.out.println();
            System.out.printf(Ansi.DIM + "[tokens: %d in / %d out | %d turn(s)]" + Ansi.RESET + "%n",
                    done.totalUsage().inputTokens(),
                    done.totalUsage().outputTokens(),
                    done.loopCount());
        } else if (event instanceof EngineEvent.Error err) {
            System.err.println(Ansi.RED + "Error: " + err.message() + Ansi.RESET);
        }
    }

    private ToolRegistry createToolRegistry() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new BashTool());
        registry.register(new FileReadTool());
        registry.register(new FileWriteTool());
        registry.register(new FileEditTool());
        registry.register(new GlobTool());
        registry.register(new GrepTool());

        // Load MCP tools from config
        loadMcpTools(registry);

        return registry;
    }

    private void loadMcpTools(ToolRegistry registry) {
        Path cwd = Path.of(System.getProperty("user.dir"));
        Map<String, McpServerConfig> mcpConfigs = McpConfigLoader.load(cwd);

        if (mcpConfigs.isEmpty()) return;

        System.out.println(Ansi.DIM + "  Connecting to " + mcpConfigs.size() + " MCP server(s)..." + Ansi.RESET);

        McpClientManager mcpManager = new McpClientManager();
        mcpManager.connectAll(mcpConfigs);

        for (var server : mcpManager.allServers()) {
            if (server instanceof dev.openclaude.mcp.McpServer.Connected c) {
                System.out.println(Ansi.DIM + "  ✓ " + c.name() + " (" + c.tools().size() + " tools)" + Ansi.RESET);
            } else if (server instanceof dev.openclaude.mcp.McpServer.Failed f) {
                System.out.println(Ansi.YELLOW + "  ✗ " + f.name() + ": " + f.error() + Ansi.RESET);
            }
        }

        for (var tool : McpToolBridge.createTools(mcpManager)) {
            registry.register(tool);
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
}
