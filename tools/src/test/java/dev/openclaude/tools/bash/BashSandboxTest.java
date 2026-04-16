package dev.openclaude.tools.bash;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class BashSandboxTest {

    @Nested
    @DisplayName("Blocked commands")
    class BlockedCommands {

        @ParameterizedTest
        @ValueSource(strings = {
                "rm -rf /",
                "rm -rf / ",
                "rm -r /",
                "rm -rf ~",
                "rm -rf ../",
                "rm -f -r /",
                "rm --recursive --force /"
        })
        void blocksRecursiveDeletionOfDangerousPaths(String command) {
            var result = BashSandbox.validate(command);
            assertInstanceOf(BashSandbox.SandboxResult.Blocked.class, result,
                    "Expected command to be blocked: " + command);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "dd if=/dev/zero of=/dev/sda",
                "dd if=/dev/urandom of=/dev/nvme0n1",
                "dd if=input.img of=/dev/vda bs=4M"
        })
        void blocksDdWritesToDevices(String command) {
            var result = BashSandbox.validate(command);
            assertInstanceOf(BashSandbox.SandboxResult.Blocked.class, result,
                    "Expected command to be blocked: " + command);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "mkfs.ext4 /dev/sda1",
                "mkfs -t xfs /dev/sdb",
                "fdisk /dev/sda",
                "parted /dev/sda mklabel gpt",
                "wipefs -a /dev/sda"
        })
        void blocksDiskManipulation(String command) {
            var result = BashSandbox.validate(command);
            assertInstanceOf(BashSandbox.SandboxResult.Blocked.class, result,
                    "Expected command to be blocked: " + command);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "shutdown -h now",
                "reboot",
                "halt",
                "poweroff",
                "init 0",
                "init 6",
                "systemctl poweroff",
                "systemctl reboot"
        })
        void blocksShutdownAndReboot(String command) {
            var result = BashSandbox.validate(command);
            assertInstanceOf(BashSandbox.SandboxResult.Blocked.class, result,
                    "Expected command to be blocked: " + command);
        }

        @Test
        void blocksForkBomb() {
            var result = BashSandbox.validate(":(){ :|:& };:");
            assertInstanceOf(BashSandbox.SandboxResult.Blocked.class, result);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "echo hello > /dev/sda",
                "cat file > /dev/nvme0n1",
                "echo data > /dev/vda"
        })
        void blocksWriteToBlockDevices(String command) {
            var result = BashSandbox.validate(command);
            assertInstanceOf(BashSandbox.SandboxResult.Blocked.class, result,
                    "Expected command to be blocked: " + command);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "chmod -R 777 /",
                "chown -R root:root /"
        })
        void blocksRecursivePermissionChangesOnRoot(String command) {
            var result = BashSandbox.validate(command);
            assertInstanceOf(BashSandbox.SandboxResult.Blocked.class, result,
                    "Expected command to be blocked: " + command);
        }
    }

    @Nested
    @DisplayName("Allowed commands (no false positives)")
    class AllowedCommands {

        @ParameterizedTest
        @ValueSource(strings = {
                "rm -rf ./build",
                "rm -rf build/",
                "rm file.txt",
                "rm -f temp.log",
                "ls -la /",
                "echo hello",
                "cat /dev/null",
                "echo test > /dev/null",
                "echo test > output.txt",
                "chmod 755 script.sh",
                "chmod -R 755 ./src",
                "chown user:group file.txt",
                "dd if=input.img of=output.img bs=4M",
                "git status",
                "npm install",
                "gradle build"
        })
        void allowsSafeCommands(String command) {
            var result = BashSandbox.validate(command);
            assertInstanceOf(BashSandbox.SandboxResult.Allowed.class, result,
                    "Expected command to be allowed: " + command);
        }

        @Test
        void allowsNullCommand() {
            var result = BashSandbox.validate(null);
            assertInstanceOf(BashSandbox.SandboxResult.Allowed.class, result);
        }

        @Test
        void allowsBlankCommand() {
            var result = BashSandbox.validate("   ");
            assertInstanceOf(BashSandbox.SandboxResult.Allowed.class, result);
        }
    }

    @Nested
    @DisplayName("SandboxResult properties")
    class ResultProperties {

        @Test
        void blockedResultContainsReason() {
            var result = BashSandbox.validate("rm -rf /");
            assertInstanceOf(BashSandbox.SandboxResult.Blocked.class, result);
            var blocked = (BashSandbox.SandboxResult.Blocked) result;
            assertFalse(blocked.reason().isBlank(), "Blocked reason should not be blank");
        }
    }
}
