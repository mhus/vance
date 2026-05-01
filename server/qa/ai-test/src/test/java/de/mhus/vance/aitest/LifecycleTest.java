package de.mhus.vance.aitest;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import de.mhus.vance.brain.VanceBrainApplication;
import java.time.Duration;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Session and engine lifecycle tests. Verifies the new spec
 * ({@code specification/session-lifecycle.md}):
 *
 * <ul>
 *   <li><b>Session-create:</b> session reaches {@code IDLE} after
 *       bootstrap, lifecycle properties from arthur recipe's
 *       foot-profile block (onDisconnect=SUSPEND, onIdle=NONE) are
 *       persisted on the session document.</li>
 *   <li><b>Engine status mapping:</b> the chat-process is initially
 *       {@code IDLE} (post-start) — {@code INIT} is transient and the
 *       engine never sits in it long enough to observe.</li>
 *   <li><b>WS process-stop:</b> {@code /stop} triggers the
 *       {@code process-stop} WS handler; engine.stop runs on the lane;
 *       chat-process transitions to {@code CLOSED} with
 *       {@code closeReason=STOPPED}.</li>
 *   <li><b>Disconnect-cascade:</b> a {@code /disconnect} on a foot
 *       session triggers the suspend-cascade — engines that survived
 *       the stop go to {@code SUSPENDED}, session document gets
 *       {@code suspendCause=DISCONNECT} and a {@code deleteAt} stamp.</li>
 * </ul>
 *
 * <p>The test does not require an LLM round-trip — it asserts on
 * Mongo state right after the lifecycle hooks fire. That keeps it
 * deterministic in CI even when the model is rate-limited.
 */
@SpringBootTest(
        classes = VanceBrainApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("aitest")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LifecycleTest {

    private static final FootProcess foot = new FootProcess();

    private static final String TENANT = "acme";
    private static final String SEED_PROJECT = "instant-hole";
    private static final String CHAT_PROCESS_NAME = "chat";

    @Autowired
    private MongoTemplate mongo;

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

    @Test
    void sessionLifecycle_stop_disconnect_suspendCascade() throws Exception {
        // ─── 1. Connect + open session.
        FootProcess.CommandResult connect = foot.command("/connect");
        assertThat(connect.matched()).as("/connect should match").isTrue();
        boolean opened = pollUntil(Duration.ofSeconds(30),
                () -> Boolean.TRUE.equals(foot.state().get("connectionOpen")));
        assertThat(opened).as("WebSocket should reach connectionOpen=true").isTrue();

        FootProcess.CommandResult create = foot.command("/session-create " + SEED_PROJECT);
        assertThat(create.matched()).as("/session-create should match").isTrue();

        // ─── 2. Wait until the chat-process exists in Mongo.
        Document chatProcess = pollForOne(
                "think_processes",
                Filters.and(
                        Filters.eq("tenantId", TENANT),
                        Filters.eq("name", CHAT_PROCESS_NAME)),
                Duration.ofSeconds(20));
        assertThat(chatProcess)
                .as("chat-process should appear within 20s")
                .isNotNull();
        String sessionId = chatProcess.getString("sessionId");
        String chatProcessId = chatProcess.getObjectId("_id").toHexString();
        assertThat(sessionId).as("chat-process must reference its session").isNotNull();

        // ─── 3. Verify session was bootstrapped with foot-profile lifecycle config.
        boolean sessionReady = pollUntil(Duration.ofSeconds(15), () -> {
            Document s = findOne("sessions", Filters.eq("sessionId", sessionId));
            return s != null
                    && "IDLE".equals(s.getString("status"))
                    && "SUSPEND".equals(s.getString("onDisconnect"));
        });
        assertThat(sessionReady)
                .as("session should reach IDLE with onDisconnect=SUSPEND (foot-profile)")
                .isTrue();

        Document session = findOne("sessions", Filters.eq("sessionId", sessionId));
        assertThat(session).isNotNull();
        assertThat(session.getString("profile"))
                .as("session profile should be 'foot' (from aitest config)")
                .isEqualTo("foot");
        assertThat(session.getString("onDisconnect"))
                .as("foot-profile session should suspend on disconnect")
                .isEqualTo("SUSPEND");
        assertThat(session.getString("onIdle"))
                .as("foot-profile sessions should NOT auto-suspend on idle")
                .isEqualTo("NONE");
        assertThat(session.getString("onSuspend"))
                .as("foot-profile session keeps suspended sessions around")
                .isEqualTo("KEEP");
        // suspendKeepDurationMs defaults to 24h from the recipe; safety check.
        assertThat(session.getLong("suspendKeepDurationMs"))
                .as("suspendKeepDurationMs should come from arthur.profiles.foot.session")
                .isEqualTo(86_400_000L);

        // The chat-process should be IDLE (started, no work in flight).
        boolean chatIdle = pollUntil(Duration.ofSeconds(10), () -> {
            Document p = findOne("think_processes", Filters.eq("_id", chatProcess.getObjectId("_id")));
            return p != null && "IDLE".equals(p.getString("status"));
        });
        assertThat(chatIdle)
                .as("chat-process should reach IDLE shortly after start (INIT is transient)")
                .isTrue();

        // ─── 4. Trigger /stop and verify CLOSED + closeReason=STOPPED.
        FootProcess.CommandResult stop = foot.command("/stop");
        assertThat(stop.matched()).as("/stop should match").isTrue();

        boolean chatClosed = pollUntil(Duration.ofSeconds(15), () -> {
            Document p = findOne("think_processes", Filters.eq("_id", chatProcess.getObjectId("_id")));
            return p != null
                    && "CLOSED".equals(p.getString("status"))
                    && "STOPPED".equals(p.getString("closeReason"));
        });
        assertThat(chatClosed)
                .as("chat-process should be CLOSED with closeReason=STOPPED after /stop")
                .isTrue();

        // ─── 5. /disconnect — foot-profile policy is SUSPEND. With the
        //         only chat-process already CLOSED, the cascade has
        //         nothing to suspend at the engine level, but the
        //         session itself transitions to SUSPENDED with
        //         suspendCause=DISCONNECT and a stamped deleteAt.
        FootProcess.CommandResult disconnect = foot.command("/disconnect");
        assertThat(disconnect.matched()).as("/disconnect should match").isTrue();

        boolean sessionSuspended = pollUntil(Duration.ofSeconds(15), () -> {
            Document s = findOne("sessions", Filters.eq("sessionId", sessionId));
            return s != null
                    && "SUSPENDED".equals(s.getString("status"))
                    && "DISCONNECT".equals(s.getString("suspendCause"));
        });
        assertThat(sessionSuspended)
                .as("session should reach SUSPENDED with suspendCause=DISCONNECT after /disconnect")
                .isTrue();

        Document suspendedSession = findOne("sessions", Filters.eq("sessionId", sessionId));
        assertThat(suspendedSession).isNotNull();
        assertThat(suspendedSession.get("suspendedAt"))
                .as("suspendedAt should be stamped at suspend-time")
                .isNotNull();
        assertThat(suspendedSession.get("deleteAt"))
                .as("deleteAt should be stamped — sweeper uses it later")
                .isNotNull();
        assertThat(suspendedSession.get("boundConnectionId"))
                .as("disconnect should clear the connection bind")
                .isNull();
    }

    // ─── Mongo / poll helpers ───

    private @Nullable Document findOne(String collection, Bson filter) {
        return mongo.getCollection(collection).find(filter).first();
    }

    private @Nullable Document pollForOne(String collection, Bson filter, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        Document found = mongo.getCollection(collection).find(filter)
                .sort(Sorts.descending("_id"))
                .first();
        while (found == null && System.nanoTime() < deadline) {
            Thread.sleep(500);
            found = mongo.getCollection(collection).find(filter)
                    .sort(Sorts.descending("_id"))
                    .first();
        }
        return found;
    }

    private interface BoolSupplier {
        boolean getAsBoolean() throws Exception;
    }

    private static boolean pollUntil(Duration timeout, BoolSupplier check) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (check.getAsBoolean()) return true;
            Thread.sleep(200);
        }
        return false;
    }
}
