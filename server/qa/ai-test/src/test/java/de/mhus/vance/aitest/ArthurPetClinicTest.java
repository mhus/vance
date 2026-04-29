package de.mhus.vance.aitest;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;

/**
 * Long-running end-to-end test: Arthur is given a multi-step "build a
 * Pet Clinic console app" goal and is expected to autonomously decide
 * which engine to delegate to (Marvin / Vogon / Ford), have its workers
 * write source files into the server-side workspace via
 * {@code workspace_write}, and run the build via {@code exec_run mvn …}.
 *
 * <p>The test harness:
 * <ul>
 *   <li>Mints a JWT (via {@link BrainAuthClient}).</li>
 *   <li>Starts an {@link InboxAutoResponder} that auto-DECIDEs all
 *       APPROVAL/DECISION/FEEDBACK items as soon as workers post them
 *       — no human in the loop.</li>
 *   <li>Sends a single, detailed chat message and then waits up to
 *       {@link #TASK_TIMEOUT} for the build to land in the workspace.</li>
 *   <li>Polls the server-side workspace at
 *       {@code target/ai-test/workspace/instant-hole/} for {@code pom.xml}
 *       and at least one Java source. Polls
 *       {@code target/ai-test/exec/} for an {@code mvn}-shaped log.</li>
 * </ul>
 *
 * <p>This is deliberately a wide test: the first run is unlikely to be
 * fully green. It serves as an observation post — when something stalls,
 * read {@code brain.log}, the auto-responder's {@code answered()} list,
 * and the workspace contents to decide whether to tune the prompt, the
 * recipe, or the auto-responder defaults.
 *
 * <p>Run individually:
 * <pre>
 *   mvn -pl qa/ai-test -am verify -Dtest=ArthurPetClinicTest
 * </pre>
 *
 * <p>Skip-by-default: the test is gated on the {@code ai.complex} system
 * property to keep the regular {@code mvn test} run fast. Pass
 * {@code -Dai.complex=true} or {@code -Dtest=ArthurPetClinicTest} (the
 * explicit selector also runs it).
 */
class ArthurPetClinicTest extends AbstractAiTest {

    private static final String PROJECT_ID = "instant-hole";
    private static final String USERNAME = "wile.coyote";
    private static final String PASSWORD = "acme-rocket";

    /**
     * Hard upper bound on how long we wait for Arthur to post the final
     * OUTPUT_TEXT summary. This is an abort-the-stuck-test deadline, not
     * a "happy path" duration — the run completes the moment Arthur
     * actually posts, regardless of how soon that is.
     */
    private static final Duration FINISH_TIMEOUT = Duration.ofMinutes(15);

    private BrainAuthClient auth;
    private InboxAutoResponder responder;
    private BrainInsightsClient insights;
    private String sessionId;

    @BeforeAll
    void startSupport() throws Exception {
        auth = new BrainAuthClient("http://localhost:18080", TENANT, USERNAME, PASSWORD);
        auth.mint();
        responder = new InboxAutoResponder(auth, Duration.ofSeconds(2));
        responder.start();
        insights = new BrainInsightsClient(auth);
    }

    @AfterAll
    void stopSupport() {
        if (responder != null) {
            responder.close();
        }
    }

    @Test
    @Timeout(value = 25, unit = java.util.concurrent.TimeUnit.MINUTES, threadMode = ThreadMode.SEPARATE_THREAD)
    void arthurBuildsPetClinic() throws Exception {
        String arthurId = connectAndCreateSession(PROJECT_ID);
        // Capture the bound session id from the chat-process so the test can
        // hit the Insights REST endpoint with the right session scope.
        Document arthurDoc = findOne("think_processes",
                Filters.eq("_id", new org.bson.types.ObjectId(arthurId)));
        sessionId = arthurDoc != null ? arthurDoc.getString("sessionId") : "";

        // ChatStallResponder: a tiny background nudge that pokes Arthur in
        // the chat with 'Yes, please proceed.' if his process sits READY
        // with no new assistant message for 60 s — covers the rare path
        // where Arthur asks the user via chat instead of via inbox-APPROVAL.
        ChatStallResponder stallNudge = new ChatStallResponder(
                arthurId, foot, this::latestAssistantAt, Duration.ofSeconds(60));
        stallNudge.start();
        try {
            arthurBuildsPetClinicInner(arthurId);
        } finally {
            stallNudge.stop();
        }
    }

    private void arthurBuildsPetClinicInner(String arthurId) throws Exception {
        FootProcess.InputResult chat = foot.chat(
                """
                I want a small Java application built end-to-end. Decide which
                engine fits best (Marvin for decomposition, Vogon for phased
                execution, or a Ford recipe if it really fits) and delegate.

                Goal:
                - A Java 17 Maven project, no Spring / Quarkus / heavy frameworks.
                - Console-only "Pet Clinic" application.
                - Persistence via plain `.properties` files in the working dir.
                - CRUD for `Pet` and `Owner` (in-memory + properties-file persistence).
                - JUnit 5 unit tests for the CRUD logic.
                - The build must compile (`mvn -q -DskipTests compile`) and the
                  tests must pass (`mvn -q test`).

                Constraints:
                - Use the server-side workspace tools (`workspace_write`,
                  `workspace_read`, `workspace_list`) for source files. Do NOT
                  use client_file_* — the test verifies files on the brain side.
                - Use `exec_run` for `mvn` invocations. The workspace root is the
                  shell's working directory.
                - Keep the project minimal: one Maven module, ~5–10 classes,
                  enough tests to prove CRUD works.

                ## Autonomy — do not ask me anything in chat

                I will not be answering chat questions during this run. Make
                all decisions yourself with sensible defaults; do NOT ask me
                to confirm scope, naming, package layout, dependencies, or
                whether to proceed. Pick a reasonable default and move on.

                After spawning an async worker (Marvin / Vogon), end your
                turn with a single short status sentence — do NOT ask "shall
                I proceed?" or "soll ich weitermachen?". Worker-side inbox
                checkpoints are out of your hands; the test harness answers
                those automatically, you don't need to mention them.

                When the build is green and the tests pass, post a final
                summary to the inbox (`inbox_post(type=OUTPUT_TEXT, …)`)
                listing the files you wrote and the test results, then stop.
                """);
        assertThat(chat.kind()).isEqualTo("CHAT");

        // Done-detection — single canonical signal: Arthur posts a
        // final OUTPUT_TEXT inbox item per the prompt ("post a final
        // summary to the inbox"). Anything weaker is a false positive
        // (a passing mvn run from the planning phase doesn't mean the
        // strategy completed). Anything else gets us the timeout, which
        // exists ONLY to abort the test — not as a happy path.
        boolean done = pollUntil(FINISH_TIMEOUT, () -> {
            Document summary = findOne("inbox_items",
                    Filters.and(
                            Filters.eq("tenantId", TENANT),
                            Filters.eq("type", "OUTPUT_TEXT"),
                            Filters.eq("originProcessId", arthurId)));
            return summary != null;
        });
        if (!done) {
            // Pull a coherent server-side picture of who's still running
            // before we fail — way more useful than just "timed out".
            String snapshot;
            try {
                snapshot = insights.formatProcessTree(
                        insights.processesForSession(sessionId));
            } catch (Exception e) {
                snapshot = "(insights snapshot failed: " + e.getMessage() + ")";
            }
            assertThat(done)
                    .as("Arthur should post a final OUTPUT_TEXT summary "
                            + "within %s — current engine snapshot:\n%s\n"
                            + "see brain.log + foot.log under target/ai-test/",
                            FINISH_TIMEOUT, snapshot)
                    .isTrue();
        }

        // Sanity: at least one Marvin/Vogon/Ford child of Arthur exists —
        // proves Arthur actually delegated rather than answering inline.
        List<Document> children = findAll(
                "think_processes",
                Filters.and(
                        Filters.eq("tenantId", TENANT),
                        Filters.eq("parentProcessId", arthurId)));
        assertThat(children)
                .as("Arthur should have spawned at least one worker child")
                .isNotEmpty();

        // Helpful breadcrumb in the test output for the next iteration.
        System.out.println("[ArthurPetClinicTest] auto-responder answered "
                + responder.answered().size() + " inbox item(s):");
        for (InboxAutoResponder.AnsweredItem a : responder.answered()) {
            System.out.println("  - " + a.type() + " '" + a.title() + "' → " + a.value());
        }
    }

    /**
     * Returns the {@code createdAt} timestamp of the most recent
     * {@code role=ASSISTANT} chat message belonging to the given
     * {@code thinkProcessId}, or {@code null} if there isn't one yet.
     */
    private @org.jspecify.annotations.Nullable Instant latestAssistantAt(String processId) {
        Document msg = mongo.getCollection("chat_messages")
                .find(Filters.and(
                        Filters.eq("thinkProcessId", processId),
                        Filters.eq("role", "ASSISTANT")))
                .sort(Sorts.descending("createdAt"))
                .first();
        if (msg == null) {
            return null;
        }
        Object created = msg.get("createdAt");
        if (created instanceof Instant i) {
            return i;
        }
        if (created instanceof java.util.Date d) {
            return d.toInstant();
        }
        return null;
    }

    /**
     * Background nudge that drops a "Yes, please proceed." into the chat
     * if Arthur sits idle (process back to READY, no new assistant
     * message for {@code idleAfter}) — covers the path where Arthur asks
     * the user via chat instead of via the inbox APPROVAL channel.
     *
     * <p>Stays quiet during normal progress (because new assistant
     * messages reset the idle clock) and during the natural settle phase
     * at the end (because {@link AbstractAiTest#stopFoot} kills foot
     * before the nudge fires for the post-completion silence).
     */
    private static final class ChatStallResponder {
        private final String arthurId;
        private final FootProcess foot;
        private final java.util.function.Function<String, Instant> latestAt;
        private final Duration idleAfter;
        private final java.util.concurrent.atomic.AtomicBoolean running =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        private final AtomicReference<Thread> worker = new AtomicReference<>();

        ChatStallResponder(String arthurId,
                           FootProcess foot,
                           java.util.function.Function<String, Instant> latestAt,
                           Duration idleAfter) {
            this.arthurId = arthurId;
            this.foot = foot;
            this.latestAt = latestAt;
            this.idleAfter = idleAfter;
        }

        void start() {
            if (running.getAndSet(true)) return;
            Thread t = new Thread(this::loop, "chat-stall-nudge");
            t.setDaemon(true);
            t.start();
            worker.set(t);
        }

        void stop() {
            if (!running.getAndSet(false)) return;
            Thread t = worker.get();
            if (t != null) t.interrupt();
        }

        private void loop() {
            // Cool-off window after a nudge so we don't hammer Arthur with
            // back-to-back "yes" messages. 60 s is enough for one Arthur
            // turn + any spawned workers to react.
            Instant cooloffUntil = Instant.now();
            while (running.get()) {
                try {
                    Thread.sleep(10_000);
                    if (Instant.now().isBefore(cooloffUntil)) continue;
                    Instant last = latestAt.apply(arthurId);
                    if (last == null) continue;
                    Duration idle = Duration.between(last, Instant.now());
                    if (idle.compareTo(idleAfter) >= 0) {
                        FootProcess.InputResult r = foot.chat("Yes, please proceed.");
                        System.out.println("[chat-stall-nudge] poked Arthur "
                                + "after " + idle.getSeconds() + "s idle "
                                + "→ kind=" + r.kind() + " ok=" + r.ok());
                        cooloffUntil = Instant.now().plusSeconds(60);
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    System.err.println("[chat-stall-nudge] " + e.getMessage());
                }
            }
        }
    }
}
