package de.mhus.vance.aitest;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.model.Filters;
import java.time.Duration;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;

/**
 * End-to-end test: Arthur (chat orchestrator) delegates an operational
 * lookup to a Ford-based worker recipe. The user message is phrased as a
 * direct, single-step lookup the {@code quick-lookup} or {@code analyze}
 * recipe is built for.
 *
 * <p>What is verified:
 * <ul>
 *   <li>{@code /session-create} brings up an Arthur chat-process.</li>
 *   <li>Sending a chat message triggers Arthur to call
 *       {@code process_create} → a child process appears in
 *       {@code think_processes} with {@code thinkEngine = "ford"} and
 *       {@code parentProcessId = arthur.id}.</li>
 *   <li>The child carries a Ford-family recipe ({@code ford},
 *       {@code quick-lookup}, {@code analyze}, {@code code-read},
 *       {@code web-research}, {@code marvin-worker}, or {@code default}).</li>
 *   <li>The Arthur turn produces at least one assistant chat message
 *       (proving the pipeline is alive end-to-end).</li>
 * </ul>
 *
 * <p>This is an LLM-driven test — Gemini Flash via the alias resolver. It
 * requires {@code confidential/init-settings.yaml} to carry a working
 * {@code ai.provider.gemini.apiKey}. If the LLM picks a different
 * delegation path, the test fails — that is intentional and is the signal
 * that the Arthur prompt or recipe descriptions need tuning.
 */
class ArthurFordRecipeTest extends AbstractAiTest {

    /**
     * Recipes whose engine is Ford in the bundled catalog. We accept any of
     * these as "Arthur correctly delegated to a Ford worker".
     */
    private static final List<String> FORD_RECIPES = List.of(
            "default", "ford", "quick-lookup", "analyze", "code-read",
            "web-research", "marvin-worker");

    @Test
    void arthurDelegatesLookupToFordWorker() throws Exception {
        String arthurId = connectAndCreateSession("instant-hole");

        // Phrase the request so Arthur should reach for quick-lookup /
        // analyze (single concrete data lookup). The answer doesn't matter
        // — we are testing the spawn path, not Gemini's prose.
        FootProcess.InputResult chat = foot.chat(
                "Please look up the current server time and reply with it.");
        assertThat(chat.kind()).isEqualTo("CHAT");

        // Arthur's tool call lands in Mongo as a new think-process with
        // engine=ford and parentProcessId=arthurId. Poll generously — a
        // cold LLM round-trip + spawn can take ~30-90s on Flash.
        Document fordWorker = pollForOne(
                "think_processes",
                Filters.and(
                        Filters.eq("tenantId", TENANT),
                        Filters.eq("thinkEngine", "ford"),
                        Filters.eq("parentProcessId", arthurId)),
                Duration.ofSeconds(120));
        assertThat(fordWorker)
                .as("Arthur should spawn a Ford-engine child within 120s — "
                        + "see " + foot.workdir().resolve("foot.log") + " and "
                        + "target/ai-test/brain.log on failure")
                .isNotNull();

        String recipe = fordWorker.getString("recipeName");
        assertThat(recipe)
                .as("Arthur should pick a Ford-family recipe — got '%s'", recipe)
                .isIn(FORD_RECIPES);

        // Pipeline-of-life check: Arthur replies in chat. The reply does
        // NOT have to contain the time literally — it can also be the
        // "I asked the worker, will report back" turn.
        boolean arthurReplied = pollUntil(Duration.ofSeconds(30), () -> {
            Document msg = findOne("chat_messages",
                    Filters.and(
                            Filters.eq("thinkProcessId", arthurId),
                            Filters.eq("role", "ASSISTANT")));
            return msg != null;
        });
        assertThat(arthurReplied)
                .as("Arthur should produce at least one ASSISTANT chat message")
                .isTrue();
    }
}
