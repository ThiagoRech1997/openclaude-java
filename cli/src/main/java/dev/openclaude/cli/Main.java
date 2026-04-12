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
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(
        name = "openclaude",
        mixinStandardHelpOptions = true,
        version = "openclaude-java 0.1.0",
        description = "Open-source coding agent CLI for multiple LLM providers."
)
public class Main implements Callable<Integer> {

    private static final String DIM = "\u001b[90m";
    private static final String RED = "\u001b[31m";
    private static final String YELLOW = "\u001b[33m";
    private static final String CYAN = "\u001b[36m";
    private static final String RESET = "\u001b[0m";

    @Parameters(index = "0", arity = "0..1", description = "Initial prompt to send.")
    private String prompt;

    @Option(names = {"-m", "--model"}, description = "Model to use.")
    private String model;

    @Option(names = {"--system"}, description = "System prompt.",
            defaultValue = "You are a helpful coding assistant. You have access to tools for reading, writing, and editing files, running bash commands, and searching code. Use them when appropriate to help the user.")
    private String systemPrompt;

    @Override
    public Integer call() {
        AppConfig config = AppConfig.load();

        if (model != null) {
            config = new AppConfig(config.apiKey(), model, config.baseUrl(), config.provider(), config.maxTokens());
        }

        if (prompt == null || prompt.isBlank()) {
            System.out.println("openclaude-java v0.1.0");
            System.out.println("Usage: openclaude \"your prompt here\"");
            System.out.println("Interactive REPL mode coming in Phase 3.");
            return 0;
        }

        config.validate();

        LlmClient client = LlmClientFactory.create(config);
        ToolRegistry tools = createToolRegistry();
        Path cwd = Path.of(System.getProperty("user.dir"));

        System.out.println(DIM + config.provider() + " / " + config.model()
                + " | " + tools.size() + " tools" + RESET);
        System.out.println();

        QueryEngine engine = new QueryEngine(
                client, tools, config.model(), systemPrompt,
                config.maxTokens(), cwd, this::handleEvent
        );

        engine.run(prompt);

        return 0;
    }

    private void handleEvent(EngineEvent event) {
        if (event instanceof EngineEvent.Stream s) {
            handleStreamEvent(s.event());
        } else if (event instanceof EngineEvent.ToolExecutionStart tes) {
            System.out.println();
            System.out.print(CYAN + "  ⚡ " + tes.toolName() + RESET + " ");
            System.out.flush();
        } else if (event instanceof EngineEvent.ToolExecutionEnd tee) {
            if (tee.result().isError()) {
                System.out.println(RED + "✗" + RESET);
                System.out.println(DIM + "  Error: " + truncate(tee.result().content(), 200) + RESET);
            } else {
                System.out.println(DIM + "✓" + RESET);
            }
        } else if (event instanceof EngineEvent.Done done) {
            System.out.println();
            System.out.printf(DIM + "[tokens: %d in / %d out | %d loop(s)]" + RESET + "%n",
                    done.totalUsage().inputTokens(),
                    done.totalUsage().outputTokens(),
                    done.loopCount());
        } else if (event instanceof EngineEvent.Error err) {
            System.err.println(RED + "Error: " + err.message() + RESET);
        }
    }

    private void handleStreamEvent(StreamEvent event) {
        if (event instanceof StreamEvent.TextDelta td) {
            System.out.print(td.text());
            System.out.flush();
        } else if (event instanceof StreamEvent.ThinkingDelta th) {
            System.out.print(DIM + th.thinking() + RESET);
            System.out.flush();
        } else if (event instanceof StreamEvent.Error err) {
            System.err.println(RED + "Stream error: " + err.message() + RESET);
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
        return registry;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
}
