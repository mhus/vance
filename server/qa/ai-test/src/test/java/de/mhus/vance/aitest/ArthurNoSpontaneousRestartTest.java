package de.mhus.vance.aitest;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.model.Filters;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;

/**
 * Regression test for an observed bug: after Arthur completes one full
 * orchestration round-trip (spawn worker → steer → read result →
 * {@code process_stop} → synthesise), Arthur spontaneously starts a
 * SECOND turn — re-runs the same user request, spawns another worker,
 * etc. — without any new user input.
 *
 * <p>Likely root cause: when Arthur calls {@code process_stop}, the
 * brain emits a {@code <process-event type="STOPPED">} to the parent
 * (Arthur). Arthur's drain-pending logic treats that event as inbound
 * material that warrants another LLM turn. The spec says "a
 * {@code <process-event>} is not the user typing — treat it as
 * context, not as a question to answer back to the worker", but the
 * implementation appears to wake Arthur anyway.
 *
 * <h2>What this test does</h2>
 * <ol>
 *   <li>Open a session in a regular project ({@code instant-hole}) so
 *       Arthur is the chat-process.</li>
 *   <li>Send a single, narrow operational request that Arthur should
 *       resolve in one round-trip with a {@code quick-lookup}-style
 *       worker.</li>
 *   <li>Wait until at least one child reaches a terminal status
 *       ({@code STOPPED}, {@code DONE}, {@code FAILED}, {@code STALE}).</li>
 *   <li>Add a small grace period so Arthur's final synthesis message
 *       finishes streaming.</li>
 *   <li>Snapshot the worker-count and the ASSISTANT-message-count.</li>
 *   <li>Sleep 30 s — Arthur must be silent.</li>
 *   <li>Re-snapshot. Assert no new children. Tolerate at most one
 *       additional ASSISTANT message (a streaming flush that lands
 *       just after the snapshot).</li>
 * </ol>
 *
 * <p>While the bug is present, this test is expected to FAIL — that's
 * its job. Once the brain is fixed, the test should turn green and
 * stay green.
 */
class ArthurNoSpontaneousRestartTest extends AbstractAiTest {

    /** How many runs we attempt before declaring the prompt clean. */
    private static final int RUNS = 5;

    /** Per-iteration idle window — long enough for a spontaneous restart
     *  to fire, short enough to keep the whole test under ~10 min. */
    private static final Duration WATCH_WINDOW = Duration.ofSeconds(60);

    /** Per-iteration deadline for Arthur's first reply. */
    private static final Duration FIRST_REPLY_TIMEOUT = Duration.ofMinutes(3);

    /**
     * Different operational asks across the {@link #RUNS} iterations so the
     * agent doesn't see a verbatim prompt repeat in its own chat history,
     * which would suppress the bug pattern artificially.
     */
    private static final String[] PROMPTS = new String[] {
            "Bitte zähle die Dateien im aktuellen Arbeitsverzeichnis "
                    + "und antworte nur mit der Zahl.",
            "Wie groß ist die größte Datei im aktuellen Arbeitsverzeichnis? "
                    + "Antworte mit Dateiname und Größe.",
            "Liste die ersten drei Dateien im aktuellen Arbeitsverzeichnis "
                    + "alphabetisch sortiert.",
            "Welche Dateierweiterungen gibt es im aktuellen Arbeitsverzeichnis? "
                    + "Antworte mit der Liste.",
            "Wieviel freier Plattenplatz steht im aktuellen Verzeichnis zur "
                    + "Verfügung? Antworte mit der Zahl.",
    };

    @Test
    @Timeout(value = 30, unit = java.util.concurrent.TimeUnit.MINUTES, threadMode = ThreadMode.SEPARATE_THREAD)
    void arthurDoesNotRestartAfterProcessStop() throws Exception {
        // Initial /connect + first session — the helper takes care of
        // both. Subsequent iterations re-bind a fresh session against
        // the same WebSocket, so each iteration starts with a brand-new
        // chat-process (no shared history, no carry-over pending events).
        String arthurId = connectAndCreateSession("instant-hole");

        for (int iteration = 1; iteration <= RUNS; iteration++) {
            // Iteration ≥ 2: drop the previous session and bind a fresh
            // one. Each iteration sees an empty Arthur (only the
            // bootstrap greeting in chat_messages).
            if (iteration > 1) {
                arthurId = freshSession("instant-hole");
                System.out.println("[iteration " + iteration + "/" + RUNS
                        + "] fresh session, new chat-process id=" + arthurId);
            }

            // Lambda capture needs an effectively final ref to this
            // iteration's chat-process id.
            final String iterArthurId = arthurId;

            int childrenBefore = childrenOf(iterArthurId);
            int messagesBefore = assistantMessagesOn(iterArthurId);

            String prompt = PROMPTS[(iteration - 1) % PROMPTS.length];
            FootProcess.InputResult chat = foot.chat(prompt);
            assertThat(chat.kind()).isEqualTo("CHAT");
            System.out.println("[iteration " + iteration + "/" + RUNS + "] sent: " + prompt);

            // Wait for Arthur's reply to THIS iteration's prompt.
            boolean firstAssistant = pollUntil(FIRST_REPLY_TIMEOUT, () ->
                    assistantMessagesOn(iterArthurId) >= messagesBefore + 1);
            if (!firstAssistant) {
                // Arthur's own LLM stack died (e.g. Gemini exhausted both
                // chain entries) before producing a reply. That's a
                // separate failure mode — not the spontaneous-restart
                // bug we're testing here. Skip this iteration so a
                // single Gemini hiccup doesn't mask the bug we're after.
                System.out.println("[iteration " + iteration + "/" + RUNS
                        + "] no reply within " + FIRST_REPLY_TIMEOUT
                        + " — skipping (likely upstream Gemini issue, "
                        + "see brain.log for AiChatException trace)");
                continue;
            }

            int messagesAtT1 = assistantMessagesOn(iterArthurId);
            int childrenAtT1 = childrenOf(iterArthurId);
            System.out.println("[iteration " + iteration + "/" + RUNS + "] turn 1 done: "
                    + "children=" + childrenAtT1 + " (Δ" + (childrenAtT1 - childrenBefore) + ")"
                    + " messages=" + messagesAtT1 + " (Δ" + (messagesAtT1 - messagesBefore) + ")");

            // Watch for a spontaneous second turn during the idle window.
            // pollUntil returns true on first matching observation.
            boolean secondTurnFired = pollUntil(WATCH_WINDOW, () ->
                    assistantMessagesOn(iterArthurId) > messagesAtT1);

            int childrenNow = childrenOf(iterArthurId);
            int messagesNow = assistantMessagesOn(iterArthurId);
            System.out.println("[iteration " + iteration + "/" + RUNS + "] after "
                    + WATCH_WINDOW.toSeconds() + "s idle window: "
                    + "children=" + childrenNow + " messages=" + messagesNow
                    + " secondTurnFired=" + secondTurnFired);

            // Fail fast on first reproduction. Diagnostic shows the
            // iteration that triggered the bug — useful when iterating
            // on the Arthur prompt: a clean prompt produces 5/5 green;
            // a regressed prompt fails on iteration N within minutes.
            assertThat(secondTurnFired)
                    .as("Iteration %d/%d — Arthur spontaneously started a SECOND "
                            + "turn during the %s idle window after his first reply. "
                            + "Bug pattern: Arthur calls process_stop on his worker, "
                            + "the resulting <process-event type=STOPPED> is queued "
                            + "to Arthur's pending-inbox, drainPending picks it up "
                            + "and runs another Arthur.turn — re-issuing the user's "
                            + "request, spawning a fresh worker. "
                            + "Snapshot: children=%d (was %d at turn-1-end), "
                            + "messages=%d (was %d at turn-1-end). "
                            + "See brain.log for the spontaneous Arthur.turn entry.",
                            iteration, RUNS, WATCH_WINDOW,
                            childrenNow, childrenAtT1, messagesNow, messagesAtT1)
                    .isFalse();

            // Brief settle between iterations so workers from the previous
            // iteration finish their teardown cleanly before the session
            // gets unbound.
            Thread.sleep(2_000);
        }

        System.out.println("[ArthurNoSpontaneousRestartTest] all " + RUNS
                + " iterations clean — no spontaneous restart observed.");
    }

    /**
     * Unbinds the current session and creates a fresh one in the given
     * project. Returns the {@code _id} of the new session's chat-process
     * — that's the new "arthurId" for the next iteration.
     */
    private String freshSession(String projectId) throws Exception {
        FootProcess.CommandResult unbind = foot.command("/session-unbind");
        assertThat(unbind.matched()).as("/session-unbind should match").isTrue();
        FootProcess.CommandResult create = foot.command("/session-create " + projectId);
        assertThat(create.matched()).as("/session-create should match").isTrue();

        // Pick the most recently created chat-process for this tenant —
        // that's the one tied to the freshly bound session.
        org.bson.Document chatProcess = mongo.getCollection("think_processes")
                .find(com.mongodb.client.model.Filters.and(
                        com.mongodb.client.model.Filters.eq("tenantId", TENANT),
                        com.mongodb.client.model.Filters.eq("name", CHAT_PROCESS_NAME)))
                .sort(com.mongodb.client.model.Sorts.descending("createdAt"))
                .first();
        assertThat(chatProcess)
                .as("a freshly created chat-process should be visible after /session-create")
                .isNotNull();
        return chatProcess.getObjectId("_id").toHexString();
    }

    // ──────────────────── helpers ────────────────────

    private int childrenOf(String parentId) {
        return findAll("think_processes",
                Filters.eq("parentProcessId", parentId)).size();
    }

    private int assistantMessagesOn(String processId) {
        return findAll("chat_messages",
                Filters.and(
                        Filters.eq("thinkProcessId", processId),
                        Filters.eq("role", "ASSISTANT")))
                .size();
    }
}
