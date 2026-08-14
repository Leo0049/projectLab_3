package com.bizmcp.support;

import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;

/**
 * A real Redis for the tests that need one.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>{@code bizmcp.test.redis.port} (system property or {@code BIZMCP_TEST_REDIS_PORT}),
 *       so CI can point at a service container;</li>
 *   <li>a {@code redis-server} binary on PATH, started on a free port;</li>
 *   <li>otherwise the test is skipped rather than failed — a missing Redis is a
 *       missing tool, not a broken product.</li>
 * </ol>
 */
public final class RedisTestServer implements AutoCloseable {

    private final int port;
    private final Process process;

    private RedisTestServer(int port, Process process) {
        this.port = port;
        this.process = process;
    }

    public static RedisTestServer startOrSkip() {
        String configured = System.getProperty("bizmcp.test.redis.port",
                System.getenv("BIZMCP_TEST_REDIS_PORT"));
        if (configured != null && !configured.isBlank()) {
            return new RedisTestServer(Integer.parseInt(configured.trim()), null);
        }

        Assumptions.assumeTrue(binaryAvailable(),
                "no redis-server on PATH and no bizmcp.test.redis.port configured");

        int port = freePort();
        Process started;
        try {
            started = new ProcessBuilder("redis-server",
                    "--port", String.valueOf(port),
                    "--save", "",
                    "--appendonly", "no")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException e) {
            Assumptions.abort("could not start redis-server: " + e.getMessage());
            throw new IllegalStateException(e);
        }

        awaitReady(port, started);
        return new RedisTestServer(port, started);
    }

    private static boolean binaryAvailable() {
        try {
            Process which = new ProcessBuilder("redis-server", "--version")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return which.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void awaitReady(int port, Process process) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            if (!process.isAlive()) {
                Assumptions.abort("redis-server exited immediately");
            }
            try (ServerSocket ignored = new ServerSocket()) {
                new java.net.Socket("127.0.0.1", port).close();
                return;
            } catch (IOException e) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        Assumptions.abort("redis-server did not become reachable on port " + port);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("no free port for redis", e);
        }
    }

    public int port() {
        return port;
    }

    @Override
    public void close() {
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }
}
