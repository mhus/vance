package de.mhus.vance.aitest;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.model.Filters;
import de.mhus.vance.brain.VanceBrainApplication;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Shared lifecycle for ai-tests that drive Arthur into delegating to a
 * specific worker engine (Ford, Vogon, Zaphod). Subclasses get:
 *
 * <ul>
 *   <li>A booted brain Spring context (Mongo URI from {@link MongoFixture}).</li>
 *   <li>A foot subprocess in {@code daemon} mode reachable on
 *       {@code 127.0.0.1:18766} via the debug REST endpoints.</li>
 *   <li>{@link #connectAndCreateSession(String)} — runs {@code /connect}
 *       followed by {@code /session-create &lt;project&gt;} and waits until the
 *       chat-process row appears in Mongo. Returns the chat-process id so
 *       the subclass can poll for spawned children.</li>
 *   <li>Mongo helpers ({@link #findOne}, {@link #findAll},
 *       {@link #pollUntil}).</li>
 * </ul>
 *
 * <p>Tests run individually in their own surefire JVM (Brain context is not
 * cleanly reusable across two {@code WebEnvironment.DEFINED_PORT} tests in
 * the same JVM today). Run with {@code -Dtest=&lt;ClassName&gt;}.
 */
@SpringBootTest(
        classes = VanceBrainApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("aitest")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractAiTest {

    protected static final String TENANT = "acme";
    protected static final String CHAT_PROCESS_NAME = "chat";

    protected final FootProcess foot = new FootProcess();

    @Autowired
    protected MongoTemplate mongo;

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        MongoFixture.start();
        registry.add("spring.mongodb.uri", MongoFixture::uri);
        registry.add("spring.mongodb.database", () -> MongoFixture.DATABASE);
    }

    @BeforeAll
    void startFoot() throws Exception {
        foot.start("foot-application-aitest.yaml");
        boolean up = foot.waitForHealth(Duration.ofSeconds(60));
        assertThat(up)
                .as("foot should expose /debug/health within 60s — see "
                        + foot.workdir().resolve("foot.log"))
                .isTrue();
    }

    @AfterAll
    void stopFoot() {
        foot.stop();
    }

    /**
     * Issues {@code /connect}, waits for the WebSocket to open, then
     * {@code /session-create &lt;projectId&gt;} and polls Mongo for the
     * chat-process row. Returns its {@code _id} as a hex string so the
     * caller can later filter spawned children by {@code parentProcessId}.
     */
    protected String connectAndCreateSession(String projectId) throws Exception {
        FootProcess.CommandResult connect = foot.command("/connect");
        assertThat(connect.matched()).as("/connect should match").isTrue();
        boolean opened = pollUntil(Duration.ofSeconds(30),
                () -> Boolean.TRUE.equals(foot.state().get("connectionOpen")));
        assertThat(opened).as("WebSocket should reach connectionOpen=true").isTrue();

        FootProcess.CommandResult create = foot.command("/session-create " + projectId);
        assertThat(create.matched()).as("/session-create should match").isTrue();

        Document chatProcess = pollForOne(
                "think_processes",
                Filters.and(
                        Filters.eq("tenantId", TENANT),
                        Filters.eq("name", CHAT_PROCESS_NAME)),
                Duration.ofSeconds(15));
        assertThat(chatProcess)
                .as("chat-process should appear within 15s of /session-create")
                .isNotNull();
        return chatProcess.getObjectId("_id").toHexString();
    }

    /** Returns the latest doc matching {@code filter}, or {@code null}. */
    protected @Nullable Document findOne(String collection, Bson filter) {
        return mongo.getCollection(collection).find(filter).first();
    }

    /** Returns all docs matching {@code filter}. */
    protected List<Document> findAll(String collection, Bson filter) {
        List<Document> out = new ArrayList<>();
        mongo.getCollection(collection).find(filter).into(out);
        return out;
    }

    /**
     * Polls Mongo every 500 ms until at least one doc matches, or {@code timeout}
     * elapses. Returns the first match or {@code null} on timeout.
     */
    protected @Nullable Document pollForOne(String collection, Bson filter, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        Document found = findOne(collection, filter);
        while (found == null && System.nanoTime() < deadline) {
            Thread.sleep(500);
            found = findOne(collection, filter);
        }
        return found;
    }

    protected interface BoolSupplier {
        boolean getAsBoolean() throws Exception;
    }

    protected static boolean pollUntil(Duration timeout, BoolSupplier check) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (check.getAsBoolean()) {
                return true;
            }
            Thread.sleep(200);
        }
        return false;
    }
}
