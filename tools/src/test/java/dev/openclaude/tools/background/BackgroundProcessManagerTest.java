package dev.openclaude.tools.background;

import dev.openclaude.tools.background.BackgroundProcessManager.BackgroundProcess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisabledOnOs(OS.WINDOWS)
class BackgroundProcessManagerTest {

    private BackgroundProcessManager manager;

    @BeforeEach
    void setUp() {
        manager = new BackgroundProcessManager();
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    private Process startBash(String command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
        pb.redirectErrorStream(true);
        return pb.start();
    }

    @Nested
    @DisplayName("Process registration and lookup")
    class Registration {

        @Test
        void startProcessReturnsMonotonicIds() throws Exception {
            String id1 = manager.startProcess(startBash("echo hi"), "echo hi");
            String id2 = manager.startProcess(startBash("echo hi"), "echo hi");
            assertEquals("bg_1", id1);
            assertEquals("bg_2", id2);
        }

        @Test
        void listProcessesReturnsAllTracked() throws Exception {
            manager.startProcess(startBash("sleep 2"), "sleep 2");
            manager.startProcess(startBash("sleep 2"), "sleep 2");
            assertEquals(2, manager.listProcesses().size());
        }

        @Test
        void getProcessReturnsNullForUnknownId() {
            assertNull(manager.getProcess("bg_doesnt_exist"));
        }

        @Test
        void removeProcessDropsEntry() throws Exception {
            String id = manager.startProcess(startBash("echo hi"), "echo hi");
            assertNotNull(manager.getProcess(id));
            manager.removeProcess(id);
            assertNull(manager.getProcess(id));
        }
    }

    @Nested
    @DisplayName("Kill semantics")
    class Kill {

        @Test
        void killRunningProcessReturnsTrueAndProcessExits() throws Exception {
            String id = manager.startProcess(startBash("sleep 30"), "sleep 30");
            BackgroundProcess bp = manager.getProcess(id);
            assertTrue(bp.isRunning());

            boolean killed = manager.killProcess(id);
            assertTrue(killed);
            assertTrue(bp.process().waitFor(3, TimeUnit.SECONDS));
            assertFalse(bp.isRunning());
        }

        @Test
        void killUnknownIdReturnsFalse() {
            assertFalse(manager.killProcess("bg_nope"));
        }

        @Test
        void killAlreadyExitedReturnsFalse() throws Exception {
            String id = manager.startProcess(startBash("true"), "true");
            BackgroundProcess bp = manager.getProcess(id);
            assertTrue(bp.process().waitFor(3, TimeUnit.SECONDS));
            assertFalse(manager.killProcess(id));
        }
    }

    @Nested
    @DisplayName("Output buffer cap")
    class BufferCap {

        @Test
        void drainNeverExceedsCapAndMarksWrap() throws Exception {
            int cap = BackgroundProcessManager.MAX_BUFFER_LINES;
            int emit = cap + 5_000;

            String id = manager.startProcess(startBash("seq 1 " + emit), "seq 1 " + emit);
            BackgroundProcess bp = manager.getProcess(id);

            // Wait for process + reader thread to drain stdout fully
            assertTrue(bp.process().waitFor(15, TimeUnit.SECONDS), "seq should complete");
            bp.readerThread().join(5_000);

            List<String> drained = manager.drainOutput(id);
            // At most cap buffered lines + one wrap marker.
            assertTrue(drained.size() <= cap + 1,
                    "drained size " + drained.size() + " exceeded cap+marker " + (cap + 1));

            long wrapMarkers = drained.stream()
                    .filter(l -> l.contains("buffer wrapped"))
                    .count();
            assertEquals(1, wrapMarkers, "expected exactly one wrap marker");
        }
    }

    @Nested
    @DisplayName("Shutdown")
    class Shutdown {

        @Test
        void shutdownDestroysRunningProcessesAndClearsMap() throws Exception {
            String id = manager.startProcess(startBash("sleep 30"), "sleep 30");
            BackgroundProcess bp = manager.getProcess(id);
            manager.shutdown();
            assertTrue(bp.process().waitFor(3, TimeUnit.SECONDS));
            assertTrue(manager.listProcesses().isEmpty());
        }
    }
}
