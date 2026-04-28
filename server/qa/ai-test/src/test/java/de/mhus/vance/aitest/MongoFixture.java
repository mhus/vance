package de.mhus.vance.aitest;

import java.util.List;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Singleton MongoDB testcontainer for the ai-test module. Pinned to a fixed
 * non-default host port (47017) so the brain can connect through a stable URI
 * and so a developer can attach mongosh to it during a paused test run.
 *
 * <p>Started lazily on first call to {@link #uri()} and reused across tests
 * within the same JVM. Each test class is responsible for wiping collections
 * via {@code MongoTemplate} if it needs a fresh database. The container is
 * started without authentication — the brain's MongoTemplate is configured
 * accordingly through {@link #uri()}.
 */
public final class MongoFixture {

    /** Fixed but non-default — Mongo's standard port is 27017. */
    public static final int FIXED_PORT = 47017;

    public static final String DATABASE = "vance-aitest";

    private static volatile MongoDBContainer container;

    private MongoFixture() {}

    public static synchronized String uri() {
        ensureStarted();
        return "mongodb://localhost:" + FIXED_PORT + "/" + DATABASE;
    }

    public static synchronized void start() {
        ensureStarted();
    }

    private static void ensureStarted() {
        if (container != null && container.isRunning()) {
            return;
        }
        // Pinning the host port keeps the brain's spring config stable across
        // runs and lets a developer reach the same Mongo from outside the JVM.
        @SuppressWarnings("resource")
        MongoDBContainer c = new MongoDBContainer(DockerImageName.parse("mongo:7.0"));
        c.setPortBindings(List.of(FIXED_PORT + ":27017"));
        c.start();
        container = c;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                c.stop();
            } catch (RuntimeException ignored) {
                // best-effort cleanup at JVM exit
            }
        }, "ai-test-mongo-shutdown"));
    }
}
