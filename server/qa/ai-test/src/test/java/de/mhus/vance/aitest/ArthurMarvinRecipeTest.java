package de.mhus.vance.aitest;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.model.Filters;
import java.time.Duration;
import org.bson.Document;
import org.junit.jupiter.api.Test;

/**
 * End-to-end test: Arthur picks up a decomposable analysis request and
 * delegates it to the Marvin-engine recipe {@code marvin}. Marvin's
 * defining property is the dynamic, persistent task-tree it builds in the
 * {@code marvin_nodes} collection — so the assertion is "did Marvin start
 * growing a tree?", not "what's in it".
 *
 * <p>What is verified:
 * <ul>
 *   <li>{@code /session-create} brings up an Arthur chat-process.</li>
 *   <li>Arthur's tool call lands a child process with
 *       {@code thinkEngine = "marvin"} and {@code parentProcessId = arthur.id}.</li>
 *   <li>The recipe is the bundled {@code marvin}.</li>
 *   <li>{@code marvin_nodes} carries at least the root node for that
 *       Marvin process within a generous window
 *       (Marvin's PLAN root is created by {@code Marvin.start}).</li>
 * </ul>
 *
 * <p>LLM-driven; tune the user message or the recipe description if Arthur
 * picks the wrong path. See specification/marvin-engine.md §1, §2.
 */
class ArthurMarvinRecipeTest extends AbstractAiTest {

    @Test
    void arthurDelegatesDecompositionToMarvin() throws Exception {
        String arthurId = connectAndCreateSession("instant-hole");

        // Phrase the request so Arthur reaches for Marvin: a goal that
        // genuinely benefits from decomposition + sub-task delegation +
        // synthesis. Mention the recipe by name so the test stays focused
        // on the spawn pipeline rather than on Arthur's recipe-selection
        // intelligence (that gets its own test later).
        FootProcess.InputResult chat = foot.chat(
                "Please decompose this task and work through it: produce a brief "
                + "structured analysis (intro / two distinct angles / short "
                + "conclusion) on whether Java records replace Lombok @Value. "
                + "Use the marvin recipe so the work is split into subtasks "
                + "and synthesised at the end.");
        assertThat(chat.kind()).isEqualTo("CHAT");

        Document marvinWorker = pollForOne(
                "think_processes",
                Filters.and(
                        Filters.eq("tenantId", TENANT),
                        Filters.eq("thinkEngine", "marvin"),
                        Filters.eq("parentProcessId", arthurId)),
                Duration.ofSeconds(150));
        assertThat(marvinWorker)
                .as("Arthur should spawn a Marvin-engine child within 150s — "
                        + "see " + foot.workdir().resolve("foot.log") + " on failure")
                .isNotNull();

        String recipe = marvinWorker.getString("recipeName");
        assertThat(recipe)
                .as("Arthur should pick the bundled 'marvin' recipe — got '%s'", recipe)
                .isEqualTo("marvin");

        // Marvin's start() creates the root node in marvin_nodes (kind=PLAN
        // by recipe param). That row is the ground truth that Marvin is
        // alive and using its task-tree machinery.
        String marvinId = marvinWorker.getObjectId("_id").toHexString();
        Document rootNode = pollForOne(
                "marvin_nodes",
                Filters.eq("processId", marvinId),
                Duration.ofSeconds(60));
        assertThat(rootNode)
                .as("Marvin.start should create a root node in marvin_nodes within 60s")
                .isNotNull();
        assertThat(rootNode.getString("processId"))
                .as("root node must carry the Marvin process id")
                .isEqualTo(marvinId);
        assertThat(rootNode.get("parentId"))
                .as("the first node Marvin creates is a root (parentId == null)")
                .isNull();
    }
}
