package dev.openclaude.mcp.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openclaude.mcp.McpException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StdioTransportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @Timeout(10)
    void serverDeath_failsInFlightRequestBeforeTimeout() throws Exception {
        // Server exits shortly after start, never answering the request.
        // The request must fail as soon as the reader hits EOF — not after
        // the full 30s timeout.
        StdioTransport transport = new StdioTransport(
                "sh", List.of("-c", "sleep 0.3"), null, null);
        try {
            long start = System.nanoTime();
            assertThrows(McpException.class,
                    () -> transport.request("ping", MAPPER.createObjectNode(), 30_000));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertTrue(elapsedMs < 5_000,
                    "request should fail fast on server death, took " + elapsedMs + "ms");
        } finally {
            transport.close();
        }
    }

    @Test
    @Timeout(10)
    void deadServer_rejectsNewRequestsImmediately() throws Exception {
        StdioTransport transport = new StdioTransport(
                "sh", List.of("-c", "exit 0"), null, null);
        try {
            long deadline = System.currentTimeMillis() + 5_000;
            while (transport.isConnected() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertFalse(transport.isConnected(), "reader thread should mark transport dead on EOF");
            assertThrows(McpException.class,
                    () -> transport.request("ping", MAPPER.createObjectNode(), 30_000));
        } finally {
            transport.close();
        }
    }
}
