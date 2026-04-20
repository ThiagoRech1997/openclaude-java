package dev.openclaude.commands.loader;

import dev.openclaude.commands.CommandResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownCommandTest {

    @Test
    void execute_substitutesArguments() {
        MarkdownCommand cmd = new MarkdownCommand(
                "greet", "Greet", null, List.of(),
                "Say hi to $ARGUMENTS.", "project");

        CommandResult r = cmd.execute("world", null);

        assertEquals(CommandResult.Action.SUBMIT_PROMPT, r.action());
        assertEquals("Say hi to world.", r.output());
    }

    @Test
    void execute_missingArgsBecomesEmptyString() {
        MarkdownCommand cmd = new MarkdownCommand(
                "greet", "Greet", null, List.of(),
                "Say hi to $ARGUMENTS.", "project");

        CommandResult r = cmd.execute(null, null);

        assertEquals("Say hi to .", r.output());
    }

    @Test
    void execute_noPlaceholder_bodyReturnedAsIs() {
        MarkdownCommand cmd = new MarkdownCommand(
                "deploy", "Deploy", null, List.of(),
                "Run deploy pipeline.", "user");

        CommandResult r = cmd.execute("staging", null);

        assertEquals("Run deploy pipeline.", r.output());
    }

    @Test
    void description_includesArgumentHintAndSource() {
        MarkdownCommand cmd = new MarkdownCommand(
                "deploy", "Deploy to env", "<env>", List.of(),
                "body", "project");

        assertEquals("Deploy to env [<env>] (project)", cmd.description());
    }

    @Test
    void description_withoutArgumentHint_stillShowsSource() {
        MarkdownCommand cmd = new MarkdownCommand(
                "doc", "Generate doc", null, List.of(),
                "body", "user");

        assertEquals("Generate doc (user)", cmd.description());
    }
}
