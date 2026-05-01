package de.mhus.vance.aitest;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import de.mhus.vance.brain.VanceBrainApplication;
import java.time.Duration;
import java.time.Instant;
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
 *   <li><b>Pause flow:</b> a manually-inserted worker child of
 *       Arthur's chat-process reaches {@code PAUSED} when the
 *       {@code /pause} command (or ESC) fires; the chat-process
 *       itself stays untouched.</li>
 *   <li><b>Resume flow:</b> the WS {@code process-resume} handler
 *       transitions the worker back to {@code IDLE}.</li>
 *   <li><b>Disconnect-cascade:</b> a {@code /disconnect} on a foot
 *       session triggers the suspend-cascade — the session moves
 *       to {@code SUSPENDED} with {@code suspendCause=DISCONNECT}
 *       and a stamped {@code deleteAt}.</li>
 * </ul>
 *
 * <p>The test does not require an LLM round-trip — it inserts the
 * worker directly into Mongo to keep the timing deterministic
 * regardless of model rate-limits.
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
    private static final String WORKER_NAME = "test-worker";

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

    /**
     * Specifically targets the "user pressed /pause, no worker had
     * been spawned, chat-process was the one running" scenario — the
     * original LifecycleTest passed by accident because every test
     * inserted a child worker first; if {@code /pause} silently
     * skipped the chat-process, that case wasn't observed.
     *
     * <p>With the corrected behaviour, {@code /pause} halts the chat
     * itself when no children exist, and the next user-typed message
     * (sent via {@code process-steer}) auto-resumes the chat.
     */
    @Test
    void pause_withNoWorkers_haltsChat_andAutoResumesOnSteer() throws Exception {
        FootProcess.CommandResult connect = foot.command("/connect");
        assertThat(connect.matched()).isTrue();
        assertThat(pollUntil(Duration.ofSeconds(30),
                () -> Boolean.TRUE.equals(foot.state().get("connectionOpen")))).isTrue();

        FootProcess.CommandResult create = foot.command("/session-create " + SEED_PROJECT);
        assertThat(create.matched()).isTrue();

        Document chat = pollForOne(
                "think_processes",
                Filters.and(
                        Filters.eq("tenantId", TENANT),
                        Filters.eq("name", CHAT_PROCESS_NAME)),
                Duration.ofSeconds(20));
        assertThat(chat).isNotNull();
        Object chatId = chat.getObjectId("_id");
        String sessionId = chat.getString("sessionId");

        // Wait for chat to settle in IDLE after start.
        assertThat(pollUntil(Duration.ofSeconds(10), () -> {
            Document p = findOne("think_processes", Filters.eq("_id", chatId));
            return p != null && "IDLE".equals(p.getString("status"));
        })).isTrue();

        // ─── Pause WITHOUT any worker ─── this is the case the
        //     manual session caught: previously /pause was a no-op
        //     when there were no children, leaving the chat running.
        FootProcess.CommandResult pause = foot.command("/pause");
        assertThat(pause.matched()).isTrue();

        boolean chatPaused = pollUntil(Duration.ofSeconds(10), () -> {
            Document p = findOne("think_processes", Filters.eq("_id", chatId));
            return p != null && "PAUSED".equals(p.getString("status"));
        });
        assertThat(chatPaused)
                .as("chat must reach PAUSED even when no workers exist — "
                        + "this is the scenario the previous test missed")
                .isTrue();

        // ─── Auto-resume on user-typed steer ───
        FootProcess.InputResult steer = foot.chat("test-correction-message");
        // Don't assert on steer.ok() — the brain may take an LLM
        // turn and time out the debug call. We only care that the
        // chat-process flipped out of PAUSED on inbound user input.
        boolean chatResumed = pollUntil(Duration.ofSeconds(15), () -> {
            Document p = findOne("think_processes", Filters.eq("_id", chatId));
            String status = p == null ? null : p.getString("status");
            // Either IDLE (auto-resume happened, turn not yet running)
            // or RUNNING (auto-resume happened and turn picked up).
            return "IDLE".equals(status) || "RUNNING".equals(status);
        });
        assertThat(chatResumed)
                .as("chat should auto-resume on inbound user steer "
                        + "(PAUSED → IDLE → maybe RUNNING)")
                .isTrue();

        // Ignore steer's outcome — we don't depend on the LLM call.
        Thread.sleep(100); // let any in-flight turn settle a tick
    }

    @Test
    void sessionLifecycle_pause_resume_disconnect_suspendCascade() throws Exception {
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
        assertThat(session.getString("onIdle"))
                .as("foot-profile sessions should NOT auto-suspend on idle")
                .isEqualTo("NONE");
        assertThat(session.getString("onSuspend"))
                .as("foot-profile session keeps suspended sessions around")
                .isEqualTo("KEEP");
        assertThat(session.getLong("suspendKeepDurationMs"))
                .as("suspendKeepDurationMs should come from arthur.profiles.foot.session")
                .isEqualTo(86_400_000L);

        // The chat-process should be IDLE (started, no work in flight).
        boolean chatIdle = pollUntil(Duration.ofSeconds(10), () -> {
            Document p = findOne("think_processes", Filters.eq("_id", chatProcess.getObjectId("_id")));
            return p != null && "IDLE".equals(p.getString("status"));
        });
        assertThat(chatIdle)
                .as("chat-process should reach IDLE shortly after start")
                .isTrue();

        // ─── 4. Inject a fake "worker" as a child of the chat-process so
        //         we can exercise the pause flow without an LLM round-trip.
        Document worker = new Document()
                .append("tenantId", TENANT)
                .append("projectId", SEED_PROJECT)
                .append("sessionId", sessionId)
                .append("name", WORKER_NAME)
                .append("title", "Test worker")
                .append("thinkEngine", "ford")
                .append("status", "IDLE")
                .append("parentProcessId", chatProcessId)
                .append("pendingMessages", java.util.List.of())
                .append("activeSkills", java.util.List.of())
                .append("engineParams", new Document())
                .append("createdAt", Instant.now())
                .append("_class",
                        "de.mhus.vance.shared.thinkprocess.ThinkProcessDocument");
        mongo.getCollection("think_processes").insertOne(worker);
        Object workerObjectId = worker.get("_id");
        assertThat(workerObjectId).as("worker should have been assigned a Mongo id").isNotNull();

        // ─── 5. /pause pauses the active workers — chat-process untouched.
        FootProcess.CommandResult stop = foot.command("/pause");
        assertThat(stop.matched()).as("/pause should match").isTrue();

        boolean workerPaused = pollUntil(Duration.ofSeconds(15), () -> {
            Document p = findOne("think_processes", Filters.eq("_id", workerObjectId));
            return p != null && "PAUSED".equals(p.getString("status"));
        });
        assertThat(workerPaused)
                .as("worker should be PAUSED after /pause fired")
                .isTrue();

        // /pause now targets the whole session — chat included.
        // The chat-process should also be PAUSED, so the next user
        // input via process-steer auto-resumes (see ProcessSteerHandler).
        boolean chatPaused = pollUntil(Duration.ofSeconds(10), () -> {
            Document p = findOne("think_processes",
                    Filters.eq("_id", chatProcess.getObjectId("_id")));
            return p != null && "PAUSED".equals(p.getString("status"));
        });
        assertThat(chatPaused)
                .as("chat-process should also be PAUSED — /pause halts the whole session")
                .isTrue();

        // ─── 6. Idempotency — pressing /pause again is a no-op.
        FootProcess.CommandResult stopAgain = foot.command("/pause");
        assertThat(stopAgain.matched()).isTrue();
        // No status change expected; sleep briefly then verify worker is still PAUSED.
        Thread.sleep(500);
        Document workerAfterDoubleStop = findOne(
                "think_processes", Filters.eq("_id", workerObjectId));
        assertThat(workerAfterDoubleStop.getString("status")).isEqualTo("PAUSED");

        // ─── 6b. Inject a second worker and exercise the harder /stop
        //         broadcast: worker should go to CLOSED + STOPPED.
        Document worker2 = new Document()
                .append("tenantId", TENANT)
                .append("projectId", SEED_PROJECT)
                .append("sessionId", sessionId)
                .append("name", "test-worker-2")
                .append("title", "Test worker 2")
                .append("thinkEngine", "ford")
                .append("status", "IDLE")
                .append("parentProcessId", chatProcessId)
                .append("pendingMessages", java.util.List.of())
                .append("activeSkills", java.util.List.of())
                .append("engineParams", new Document())
                .append("createdAt", Instant.now())
                .append("_class",
                        "de.mhus.vance.shared.thinkprocess.ThinkProcessDocument");
        mongo.getCollection("think_processes").insertOne(worker2);
        Object worker2ObjectId = worker2.get("_id");

        FootProcess.CommandResult hardStop = foot.command("/stop");
        assertThat(hardStop.matched()).as("/stop should match").isTrue();

        boolean worker2Closed = pollUntil(Duration.ofSeconds(15), () -> {
            Document p = findOne("think_processes", Filters.eq("_id", worker2ObjectId));
            return p != null
                    && "CLOSED".equals(p.getString("status"))
                    && "STOPPED".equals(p.getString("closeReason"));
        });
        assertThat(worker2Closed)
                .as("worker-2 should be CLOSED with closeReason=STOPPED after /stop")
                .isTrue();

        // The PAUSED worker also goes to CLOSED — /stop targets all
        // non-CLOSED children, regardless of intermediate state.
        Document worker1AfterHardStop = findOne(
                "think_processes", Filters.eq("_id", workerObjectId));
        assertThat(worker1AfterHardStop.getString("status"))
                .as("PAUSED worker should also be CLOSED by /stop")
                .isEqualTo("CLOSED");

        // Note: chat-process MAY transition to RUNNING here. The
        // ParentNotificationListener emits a PROCESS_EVENT.STOPPED for
        // each closed child to its parent (Arthur), which appends to
        // Arthur's inbox and schedules a turn. That's by design — the
        // orchestrator should know its worker is gone. The invariant
        // is just that chat is NOT CLOSED.
        Document chatAfterHardStop = findOne("think_processes",
                Filters.eq("_id", chatProcess.getObjectId("_id")));
        assertThat(chatAfterHardStop.getString("status"))
                .as("chat-process must NOT be CLOSED by /stop — only the workers are")
                .isNotEqualTo("CLOSED");

        // ─── 7. /disconnect — foot-profile policy is SUSPEND.
        FootProcess.CommandResult disconnect = foot.command("/disconnect");
        assertThat(disconnect.matched()).as("/disconnect should match").isTrue();

        // Generous timeout: /stop triggered a parent-notification turn
        // on Arthur (LLM call). The disconnect-cascade's engine.suspend
        // is queued on Arthur's lane behind that in-flight runTurn and
        // can only fire once it completes (or errors out).
        boolean sessionSuspended = pollUntil(Duration.ofSeconds(120), () -> {
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
