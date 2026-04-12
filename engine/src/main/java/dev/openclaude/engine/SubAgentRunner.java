package dev.openclaude.engine;

import dev.openclaude.core.model.*;
import dev.openclaude.llm.LlmClient;
import dev.openclaude.tools.ToolRegistry;
import dev.openclaude.tools.ToolUseContext;
import dev.openclaude.tools.agent.AgentRunner;

import java.util.List;

/**
 * Runs a sub-agent by creating an isolated QueryEngine instance.
 * The sub-agent has its own conversation thread but shares the same
 * tools and LLM client.
 */
public class SubAgentRunner implements AgentRunner {

    private final LlmClient client;
    private final ToolRegistry toolRegistry;
    private final String model;
    private final int maxTokens;

    public SubAgentRunner(LlmClient client, ToolRegistry toolRegistry, String model, int maxTokens) {
        this.client = client;
        this.toolRegistry = toolRegistry;
        this.model = model;
        this.maxTokens = maxTokens;
    }

    @Override
    public String runAgent(String prompt, ToolUseContext parentContext) {
        String systemPrompt = "You are a sub-agent launched to handle a specific task. "
                + "Complete the task thoroughly and return a concise summary of what you did and found. "
                + "You have access to the same tools as the parent agent.";

        StringBuilder resultText = new StringBuilder();

        QueryEngine engine = new QueryEngine(
                client, toolRegistry, model, systemPrompt,
                maxTokens, parentContext.workingDirectory(),
                event -> {
                    if (event instanceof EngineEvent.Stream s) {
                        if (s.event() instanceof StreamEvent.TextDelta td) {
                            resultText.append(td.text());
                        }
                    }
                }
        );

        List<Message> messages = engine.run(prompt);

        // Extract the final assistant text
        if (resultText.length() == 0) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (messages.get(i) instanceof Message.AssistantMessage am) {
                    for (ContentBlock block : am.content()) {
                        if (block instanceof ContentBlock.Text t) {
                            resultText.append(t.text());
                        }
                    }
                    break;
                }
            }
        }

        return resultText.length() > 0 ? resultText.toString() : "(Agent completed with no text output)";
    }
}
