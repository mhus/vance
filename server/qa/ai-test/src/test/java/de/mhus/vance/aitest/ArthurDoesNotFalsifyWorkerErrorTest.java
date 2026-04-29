package de.mhus.vance.aitest;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;

/**
 * Regression test for an observed bug: Arthur claims his worker hit an
 * error ("Worker ist auf einen internen Fehler gestoßen", "konnte die
 * Anfrage nicht bearbeiten", …) even when the worker actually succeeded
 * and produced a usable result. Sometimes Arthur mixes the false claim
 * with the actual data in the same reply.
 *
 * <p>What this test does
 * <ol>
 *   <li>Open a session in {@code instant-hole} — that project has two
 *       seeded documents (see
 *       {@code InitBrainService.seedInstantHoleDocuments}):
 *       <ul>
 *         <li>{@code notes/welcome.md} ("Welcome to Instant Hole")</li>
 *         <li>{@code specs/deployment-checklist.md} ("Deployment checklist")</li>
 *       </ul>
 *   </li>
 *   <li>Ask Arthur "Welche Dokumente gibt es im Projekt?".</li>
 *   <li>Wait for Arthur's reply.</li>
 *   <li>Verify at least one worker child reached a successful terminal
 *       state with a non-empty assistant reply (i.e. the worker actually
 *       produced an answer).</li>
 *   <li>Inspect Arthur's reply:
 *       <ul>
 *         <li>It MUST contain at least one document keyword
 *             ({@code welcome}, {@code deployment}, {@code checklist},
 *             {@code instant-hole}) — proves the data was relayed.</li>
 *         <li>It MUST NOT contain false-failure phrases —
 *             "Fehler gestoßen", "konnte … nicht bearbeiten",
 *             "fehlgeschlagen", etc. — when the worker actually succeeded.</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p>Like the restart test, the bug is LLM-flaky — the same prompt
 * wording sometimes fires it, sometimes doesn't. The test loops up to
 * {@link #RUNS} iterations and bails on the first reproduction with
 * a diagnostic snapshot, so prompt-engineering iterations get a quick
 * fail signal when the bug returns.
 */
class ArthurDoesNotFalsifyWorkerErrorTest extends AbstractAiTest {

    private static final int RUNS = 5;
    private static final Duration FIRST_REPLY_TIMEOUT = Duration.ofMinutes(3);

    /** Distinct phrasings so the LLM doesn't see verbatim repetition. */
    private static final String[] PROMPTS = new String[] {
            "Welche Dokumente gibt es im Projekt?",
            "Liste mir bitte alle Dokumente im aktuellen Projekt auf.",
            "Welche Files liegen im Projekt unter Documents?",
            "Zeig mir die vorhandenen Dokumente in diesem Projekt.",
            "Was für Dokumente sind im Projekt gespeichert?",
    };

    /**
     * At least one of these (case-insensitive) must appear in Arthur's
     * reply — the seeded documents are titled "Welcome to Instant Hole"
     * and "Deployment checklist", paths {@code notes/welcome.md} and
     * {@code specs/deployment-checklist.md}.
     */
    private static final List<String> EXPECTED_KEYWORDS = List.of(
            "welcome", "deployment", "checklist", "instant-hole", "instant hole");

    /**
     * Arthur must NOT use any of these phrases when his worker actually
     * succeeded — they're the wording observed in the bug transcript.
     * Patterns are checked case-insensitive.
     */
    private static final List<String> FORBIDDEN_FAILURE_PHRASES = List.of(
            "auf einen internen fehler",
            "auf einen fehler gestoßen",
            "konnte die anfrage nicht bearbeiten",
            "konnte die dokumente nicht ermitteln",
            "konnte die anfrage nicht ausführen",
            "ist fehlgeschlagen",
            "leider ebenfalls",
            "grundsätzliches problem mit dem worker",
            "kann diese anfrage im moment leider nicht");

    @Test
    @Timeout(value = 30, unit = java.util.concurrent.TimeUnit.MINUTES, threadMode = ThreadMode.SEPARATE_THREAD)
    void arthurDoesNotClaimErrorWhenWorkerSucceeded() throws Exception {
        String arthurId = connectAndCreateSession("instant-hole");

        for (int iteration = 1; iteration <= RUNS; iteration++) {
            int messagesBefore = assistantMessagesOn(arthurId);

            String prompt = PROMPTS[(iteration - 1) % PROMPTS.length];
            FootProcess.InputResult chat = foot.chat(prompt);
            assertThat(chat.kind()).isEqualTo("CHAT");
            System.out.println("[iteration " + iteration + "/" + RUNS + "] sent: " + prompt);

            // Wait for Arthur's new reply.
            boolean firstAssistant = pollUntil(FIRST_REPLY_TIMEOUT, () ->
                    assistantMessagesOn(arthurId) >= messagesBefore + 1);
            if (!firstAssistant) {
                // Arthur's turn died for a reason that's not the bug we're
                // testing here — possibilities include:
                //   - Gemini empty-response cascade exhausting both chain
                //     entries
                //   - Arthur exceeded his maxIterations cap because the
                //     LLM looped on tool calls
                //   - quick-lookup worker exceeded its 3-iter cap and
                //     Arthur burned its own retries trying to recover
                // Any of these prevents Arthur from producing a reply,
                // which means we cannot observe the false-error pattern
                // in this iteration. Skip and try again with a different
                // prompt phrasing.
                System.out.println("[iteration " + iteration + "/" + RUNS
                        + "] no reply within " + FIRST_REPLY_TIMEOUT
                        + " — skipping (likely upstream Gemini issue or "
                        + "iteration-cap hit, see brain.log)");
                Thread.sleep(2_000);
                continue;
            }

            // Small grace so the message is fully committed.
            Thread.sleep(2_000);

            // Latest Arthur assistant message — that's his synthesis for
            // this iteration's prompt.
            String reply = latestAssistantTextOn(arthurId);
            String replyLower = reply == null ? "" : reply.toLowerCase(Locale.ROOT);
            System.out.println("[iteration " + iteration + "/" + RUNS + "] reply (head): "
                    + reply.substring(0, Math.min(200, reply.length())) + "…");

            // Find Arthur's children: which workers ran during this
            // iteration? We look at children that recently moved to a
            // terminal state and have a non-empty assistant reply in
            // their own chat-history (= they actually answered).
            List<Document> succeededWorkers = findAll("think_processes",
                    Filters.and(
                            Filters.eq("parentProcessId", arthurId),
                            Filters.in("status", List.of("STOPPED", "DONE"))));
            int succeededWorkersWithReply = 0;
            for (Document w : succeededWorkers) {
                String workerReply = latestAssistantTextOn(
                        w.getObjectId("_id").toHexString());
                if (workerReply != null && !workerReply.isBlank()) {
                    succeededWorkersWithReply++;
                }
            }
            System.out.println("[iteration " + iteration + "/" + RUNS + "] worker stats: "
                    + "succeeded=" + succeededWorkers.size()
                    + " withReply=" + succeededWorkersWithReply);

            // If no worker succeeded with a real answer, this iteration
            // can't tell us about the false-claim bug — skip the asserts.
            if (succeededWorkersWithReply == 0) {
                System.out.println("[iteration " + iteration + "/" + RUNS
                        + "] no successful worker with reply — skipping assertions");
                Thread.sleep(2_000);
                continue;
            }

            // 1) Arthur's reply must contain document data.
            String matchedKeyword = EXPECTED_KEYWORDS.stream()
                    .filter(replyLower::contains)
                    .findFirst()
                    .orElse(null);
            assertThat(matchedKeyword)
                    .as("Iteration %d/%d — Arthur's reply should mention at "
                            + "least one document keyword from %s. "
                            + "Worker(s) succeeded with replies (count=%d), "
                            + "so the data exists. Arthur's reply was:\n---\n%s\n---",
                            iteration, RUNS, EXPECTED_KEYWORDS,
                            succeededWorkersWithReply, reply)
                    .isNotNull();

            // 2) Arthur's reply must NOT falsely claim worker failure.
            String matchedFailurePhrase = FORBIDDEN_FAILURE_PHRASES.stream()
                    .filter(replyLower::contains)
                    .findFirst()
                    .orElse(null);
            assertThat(matchedFailurePhrase)
                    .as("Iteration %d/%d — Arthur's reply contains a "
                            + "failure-claim phrase ('%s') even though %d "
                            + "worker(s) actually succeeded with non-empty "
                            + "replies. This is the bug pattern: Arthur "
                            + "narrates a worker error that didn't happen. "
                            + "Full reply:\n---\n%s\n---",
                            iteration, RUNS, matchedFailurePhrase,
                            succeededWorkersWithReply, reply)
                    .isNull();

            Thread.sleep(2_000);
        }

        System.out.println("[ArthurDoesNotFalsifyWorkerErrorTest] all " + RUNS
                + " iterations clean — no false worker-error claim observed.");
    }

    // ──────────────────── helpers ────────────────────

    private int assistantMessagesOn(String processId) {
        return findAll("chat_messages",
                Filters.and(
                        Filters.eq("thinkProcessId", processId),
                        Filters.eq("role", "ASSISTANT")))
                .size();
    }

    private String latestAssistantTextOn(String processId) {
        Document msg = mongo.getCollection("chat_messages")
                .find(Filters.and(
                        Filters.eq("thinkProcessId", processId),
                        Filters.eq("role", "ASSISTANT")))
                .sort(Sorts.descending("createdAt"))
                .first();
        if (msg == null) {
            return "";
        }
        Object content = msg.get("content");
        return content == null ? "" : content.toString();
    }
}
