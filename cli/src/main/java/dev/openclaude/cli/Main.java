package dev.openclaude.cli;

import dev.openclaude.core.config.AppConfig;
import dev.openclaude.core.model.*;
import dev.openclaude.llm.LlmClient;
import dev.openclaude.llm.LlmRequest;
import dev.openclaude.llm.provider.LlmClientFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;

import java.util.List;
import java.util.concurrent.Callable;

@Command(
        name = "openclaude",
        mixinStandardHelpOptions = true,
        version = "openclaude-java 0.1.0",
        description = "Open-source coding agent CLI for multiple LLM providers."
)
public class Main implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", description = "Initial prompt to send.")
    private String prompt;

    @Option(names = {"-m", "--model"}, description = "Model to use (e.g., claude-sonnet-4-20250514).")
    private String model;

    @Option(names = {"--system"}, description = "System prompt.", defaultValue = "You are a helpful coding assistant.")
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

        System.out.println("\u001b[90m" + config.provider() + " / " + config.model() + "\u001b[0m");
        System.out.println();

        List<Message> messages = List.of(new Message.UserMessage(prompt));
        LlmRequest request = new LlmRequest(config.model(), systemPrompt, messages, config.maxTokens());

        StringBuilder fullText = new StringBuilder();

        client.streamMessage(request, event -> {
            if (event instanceof StreamEvent.TextDelta td) {
                System.out.print(td.text());
                System.out.flush();
                fullText.append(td.text());
            } else if (event instanceof StreamEvent.ThinkingDelta th) {
                System.out.print("\u001b[90m" + th.thinking() + "\u001b[0m");
                System.out.flush();
            } else if (event instanceof StreamEvent.MessageComplete mc) {
                System.out.println();
                System.out.println();
                Usage usage = mc.message().usage();
                System.out.printf("\u001b[90m[tokens: %d in / %d out]\u001b[0m%n",
                        usage.inputTokens(), usage.outputTokens());
            } else if (event instanceof StreamEvent.Error err) {
                System.err.println();
                System.err.println("\u001b[31mError: " + err.message() + "\u001b[0m");
            }
        });

        return 0;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
}
