package dev.openclaude.tools.bash;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Security sandbox that validates bash commands before execution.
 * Blocks destructive commands like rm -rf /, disk manipulation, system shutdown, etc.
 * This is a guardrail against accidental destructive commands, not a security boundary.
 */
public final class BashSandbox {

    public sealed interface SandboxResult {
        record Allowed() implements SandboxResult {}
        record Blocked(String reason) implements SandboxResult {}
    }

    private record Rule(Pattern pattern, String reason) {}

    private static final SandboxResult ALLOWED = new SandboxResult.Allowed();

    private static final List<Rule> RULES = List.of(
            // Recursive removal of root, home, or parent directories
            new Rule(
                    Pattern.compile("rm\\s+(-\\S+\\s+)*(\\s*/\\s*$|\\s*/\\s+|~|\\.\\./)"),
                    "Recursive deletion of root, home, or parent directory"),
            new Rule(
                    Pattern.compile("rm\\s+(-\\S+\\s+)*/$"),
                    "Deletion of root directory"),

            // Disk and partition manipulation
            new Rule(
                    Pattern.compile("\\bdd\\s+.*of\\s*=\\s*/dev/"),
                    "Writing directly to block device with dd"),
            new Rule(
                    Pattern.compile("\\bmkfs\\b"),
                    "Filesystem creation (mkfs)"),
            new Rule(
                    Pattern.compile("\\bfdisk\\b"),
                    "Partition editing (fdisk)"),
            new Rule(
                    Pattern.compile("\\bparted\\b"),
                    "Partition editing (parted)"),
            new Rule(
                    Pattern.compile("\\bwipefs\\b"),
                    "Wiping filesystem signatures (wipefs)"),

            // System shutdown/reboot
            new Rule(
                    Pattern.compile("\\b(shutdown|reboot|halt|poweroff)\\b"),
                    "System shutdown or reboot"),
            new Rule(
                    Pattern.compile("\\binit\\s+[06]\\b"),
                    "System shutdown or reboot via init"),
            new Rule(
                    Pattern.compile("\\bsystemctl\\s+(poweroff|reboot|halt)\\b"),
                    "System shutdown or reboot via systemctl"),

            // Fork bomb
            new Rule(
                    Pattern.compile(":\\(\\)\\s*\\{"),
                    "Fork bomb pattern detected"),

            // Writing to block devices via redirection
            new Rule(
                    Pattern.compile(">\\s*/dev/(sd|nvme|vd|hd|xvd)"),
                    "Writing to block device via redirection"),

            // Recursive chmod/chown on root
            new Rule(
                    Pattern.compile("\\bchmod\\s+(-\\S+\\s+)*\\S*R\\S*\\s+\\S+\\s+/\\s*$"),
                    "Recursive chmod on root directory"),
            new Rule(
                    Pattern.compile("\\bchown\\s+(-\\S+\\s+)*\\S*R\\S*\\s+\\S+\\s+/\\s*$"),
                    "Recursive chown on root directory")
    );

    private BashSandbox() {}

    /**
     * Validates a command against the sandbox rules.
     *
     * @param command the bash command to validate
     * @return Allowed if the command is safe, Blocked with a reason if dangerous
     */
    public static SandboxResult validate(String command) {
        if (command == null || command.isBlank()) {
            return ALLOWED;
        }

        for (Rule rule : RULES) {
            if (rule.pattern().matcher(command).find()) {
                return new SandboxResult.Blocked(rule.reason());
            }
        }

        return ALLOWED;
    }
}
