package de.mhus.vance.aitest;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.model.Filters;
import java.time.Duration;
import org.bson.Document;
import org.junit.jupiter.api.Test;

/**
 * End-to-end test: Arthur picks up a multi-perspective decision question
 * and delegates it to a Zaphod-engine council recipe
 * ({@code council-three-perspectives} or {@code council-bmad-build}).
 *
 * <p>What is verified:
 * <ul>
 *   <li>{@code /session-create} brings up an Arthur chat-process.</li>
 *   <li>Arthur's tool call lands a child process with
 *       {@code thinkEngine = "zaphod"} and {@code parentProcessId = arthur.id}.</li>
 *   <li>The recipe is a Zaphod-family one — {@code zaphod},
 *       {@code council-three-perspectives}, or {@code council-bmad-build}.</li>
 *   <li>{@code ZaphodEngine.start} writes the {@code zaphodState} into
 *       {@code engineParams} (with the head list).</li>
 * </ul>
 *
 * <p>LLM-driven; tune the user message or the recipe description if Arthur
 * picks the wrong path.
 */
class ArthurZaphodRecipeTest extends AbstractAiTest {

    @Test
    void arthurDelegatesDecisionToCouncil() throws Exception {
        String arthurId = connectAndCreateSession("instant-hole");

        // Phrase the request so Arthur should reach for a council recipe:
        // a decision with multiple sensible perspectives.
        FootProcess.InputResult chat = foot.chat(
                "I am torn between two architectures and want a multi-perspective "
                + "consultation. Please run a council with optimist, skeptic and "
                + "pragmatist perspectives on the question 'should we adopt "
                + "event sourcing for the order service?'. "
                + "Use the council-three-perspectives recipe.");
        assertThat(chat.kind()).isEqualTo("CHAT");

        Document zaphodWorker = pollForOne(
                "think_processes",
                Filters.and(
                        Filters.eq("tenantId", TENANT),
                        Filters.eq("thinkEngine", "zaphod"),
                        Filters.eq("parentProcessId", arthurId)),
                Duration.ofSeconds(150));
        assertThat(zaphodWorker)
                .as("Arthur should spawn a Zaphod-engine child within 150s — "
                        + "see " + foot.workdir().resolve("foot.log") + " on failure")
                .isNotNull();

        String recipe = zaphodWorker.getString("recipeName");
        assertThat(recipe)
                .as("Arthur should pick a Zaphod-family recipe — got '%s'", recipe)
                .isIn("council-three-perspectives", "council-bmad-build", "zaphod");

        // ZaphodEngine.start populates engineParams.zaphodState with the
        // head list parsed from the recipe. Don't pin head names — they
        // vary per recipe.
        boolean zaphodStarted = pollUntil(Duration.ofSeconds(60), () -> {
            Document fresh = findOne("think_processes",
                    Filters.eq("_id", zaphodWorker.getObjectId("_id")));
            if (fresh == null) {
                return false;
            }
            Document params = fresh.get("engineParams", Document.class);
            return params != null && params.get("zaphodState") != null;
        });
        assertThat(zaphodStarted)
                .as("ZaphodEngine.start should populate engineParams.zaphodState within 60s")
                .isTrue();
    }
}
