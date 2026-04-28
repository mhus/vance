package de.mhus.vance.aitest;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.model.Filters;
import java.time.Duration;
import org.bson.Document;
import org.junit.jupiter.api.Test;

/**
 * End-to-end test: Arthur picks up a phased-execution request and
 * delegates it to the Vogon-engine recipe {@code waterfall-feature}. The
 * user message asks for a multi-phase software task with explicit gates,
 * which the {@code waterfall-feature} recipe is purpose-built for.
 *
 * <p>What is verified:
 * <ul>
 *   <li>{@code /session-create} brings up an Arthur chat-process.</li>
 *   <li>Arthur's tool call lands a child process with
 *       {@code thinkEngine = "vogon"} and {@code parentProcessId = arthur.id}.</li>
 *   <li>The recipe is a Vogon-family one — bundled today: {@code zaphod}'s
 *       counterpart catalog has a single Vogon recipe ({@code waterfall-feature}),
 *       so we pin to that name. The default Vogon recipe ({@code vogon},
 *       if it ever ships) would also be acceptable.</li>
 *   <li>The Vogon process records a {@code strategyState} in its
 *       {@code engineParams} — proof that {@code VogonEngine.start} ran.</li>
 * </ul>
 *
 * <p>LLM-driven; tune the user message or the recipe description if Arthur
 * picks the wrong path.
 */
class ArthurVogonRecipeTest extends AbstractAiTest {

    @Test
    void arthurDelegatesPhasedTaskToWaterfallFeature() throws Exception {
        String arthurId = connectAndCreateSession("instant-hole");

        // Phrase the request so Arthur clearly recognises a multi-phase
        // task with planning + implementation + review stages — the exact
        // shape `waterfall-feature` is for.
        FootProcess.InputResult chat = foot.chat(
                "Please plan and execute a small feature in waterfall fashion: "
                + "a 'hello' REST endpoint. Use a strategy that goes through "
                + "planning, implementation and review with checkpoints. "
                + "Use the waterfall-feature recipe.");
        assertThat(chat.kind()).isEqualTo("CHAT");

        Document vogonWorker = pollForOne(
                "think_processes",
                Filters.and(
                        Filters.eq("tenantId", TENANT),
                        Filters.eq("thinkEngine", "vogon"),
                        Filters.eq("parentProcessId", arthurId)),
                Duration.ofSeconds(150));
        assertThat(vogonWorker)
                .as("Arthur should spawn a Vogon-engine child within 150s — "
                        + "see " + foot.workdir().resolve("foot.log") + " on failure")
                .isNotNull();

        String recipe = vogonWorker.getString("recipeName");
        assertThat(recipe)
                .as("Arthur should pick a Vogon-family recipe — got '%s'", recipe)
                .isIn("waterfall-feature", "vogon");

        // VogonEngine.start writes a strategyState into engineParams. We
        // don't pin the exact contents — different strategies have
        // different shapes. We just assert "something" landed there
        // within a generous window for the engine's first turn.
        boolean strategyStarted = pollUntil(Duration.ofSeconds(60), () -> {
            Document fresh = findOne("think_processes",
                    Filters.eq("_id", vogonWorker.getObjectId("_id")));
            if (fresh == null) {
                return false;
            }
            Document params = fresh.get("engineParams", Document.class);
            return params != null && params.get("strategyState") != null;
        });
        assertThat(strategyStarted)
                .as("VogonEngine.start should populate engineParams.strategyState within 60s")
                .isTrue();
    }
}
