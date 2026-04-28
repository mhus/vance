package de.mhus.vance.aitest;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import de.mhus.vance.brain.VanceBrainApplication;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Arthur + Ford end-to-end test. The user opens a session in the
 * {@code instant-hole} seed project (a regular {@code NORMAL}
 * project, so the chat-engine is Arthur), then asks a concrete
 * factual question that Arthur should delegate to a Ford-recipe
 * worker via {@code process_create}.
 *
 * <p>This validates the orchestrator pattern:
 * <ul>
 *   <li>Arthur is the chat-process (not Vance — regular project).</li>
 *   <li>Arthur picks an appropriate recipe from {@code recipe_list} and
 *       calls {@code process_create}.</li>
 *   <li>The spawned worker has {@code thinkEngine = ford} and
 *       {@code parentProcessId = arthur.id}.</li>
 *   <li>Arthur synthesises the worker's reply for the user.</li>
 * </ul>
 *
 * <p>The test is LLM-driven, so it asserts on the structural shape
 * (process tree, parent linkage, ASSISTANT replies) rather than
 * specific text content.
 */
@SpringBootTest(
        classes = VanceBrainApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("aitest")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ArthurFordTest {

    private static final FootProcess foot = new FootProcess();

    private static final String TENANT = "acme";
    private static final String USER_LOGIN = "wile.coyote";
    private static final String SEED_PROJECT = "instant-hole";

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
    void arthurDelegatesToFordWorker() throws Exception {
        // ─── Connect + open session in a regular (NORMAL) project.
        FootProcess.CommandResult connect = foot.command("/connect");
        assertThat(connect.matched()).isTrue();
        boolean opened = pollUntil(Duration.ofSeconds(30),
                () -> Boolean.TRUE.equals(foot.state().get("connectionOpen")));
        assertThat(opened).isTrue();

        FootProcess.CommandResult create = foot.command(
                "/session-create " + SEED_PROJECT);
        assertThat(create.matched())
                .as("/session-create should resolve")
                .isTrue();

        // The chat-process should run Arthur (not Vance — instant-hole is
        // a NORMAL project).
        boolean arthurAppeared = pollUntil(Duration.ofSeconds(15), () -> {
            Document p = findOne("think_processes",
                    Filters.and(
                            Filters.eq("tenantId", TENANT),
                            Filters.eq("thinkEngine", "arthur")));
            return p != null;
        });
        assertThat(arthurAppeared)
                .as("Arthur chat-process should appear in instant-hole within 15s")
                .isTrue();

        Document arthur = mongo.getCollection("think_processes")
                .find(Filters.and(
                        Filters.eq("tenantId", TENANT),
                        Filters.eq("thinkEngine", "arthur")))
                .sort(Sorts.descending("_id"))
                .first();
        assertThat(arthur).isNotNull();
        String arthurId = arthur.getObjectId("_id").toHexString();
        long arthurChildCountBefore = mongo.getCollection("think_processes")
                .countDocuments(Filters.eq("parentProcessId", arthurId));

        // ─── Ask Arthur something he must delegate. The prompt is
        //     deliberately framed so Arthur picks a Ford-recipe via
        //     process_create rather than answering from training data.
        FootProcess.InputResult chat = foot.chat(
                "Bitte liste mir die Dokumente in diesem Projekt auf — "
                        + "delegiere das an einen Worker (recipe `quick-lookup` "
                        + "ist passend), nicht selbst raten. Ich brauche die "
                        + "tatsächlichen Datei-Pfade.");
        assertThat(chat.ok())
                .as("/debug/chat should succeed; error: %s", chat.error())
                .isTrue();

        // ─── Wait for Arthur to spawn a Ford-worker. 90s gives the
        //     LLM room for tool discovery + recipe pick + spawn.
        boolean fordWorkerSpawned = pollUntil(Duration.ofSeconds(120), () -> {
            long count = mongo.getCollection("think_processes")
                    .countDocuments(Filters.and(
                            Filters.eq("parentProcessId", arthurId),
                            Filters.eq("thinkEngine", "ford")));
            return count >= 1;
        });
        assertThat(fordWorkerSpawned)
                .as("Arthur should have spawned a Ford-worker within 120s. "
                        + "If this fails: check brain.log for 'tool_use process_create'")
                .isTrue();

        // ─── Inspect the worker.
        Document worker = mongo.getCollection("think_processes")
                .find(Filters.and(
                        Filters.eq("parentProcessId", arthurId),
                        Filters.eq("thinkEngine", "ford")))
                .sort(Sorts.descending("_id"))
                .first();
        assertThat(worker).isNotNull();
        assertThat(worker.getString("recipeName"))
                .as("Ford worker should be spawned via a recipe (not raw engine)")
                .isNotNull();

        // ─── Wait until Arthur's lane has produced the synthesis reply
        //     to the user (i.e. an ASSISTANT message after the user's
        //     latest USER message in chat_messages).
        boolean replyArrived = pollUntil(Duration.ofSeconds(120), () -> {
            // Latest message in arthur's chat must be ASSISTANT, not USER.
            Document latest = mongo.getCollection("chat_messages")
                    .find(Filters.eq("thinkProcessId", arthurId))
                    .sort(Sorts.descending("createdAt"))
                    .first();
            return latest != null && "ASSISTANT".equals(latest.getString("role"));
        });
        assertThat(replyArrived)
                .as("Arthur should produce an assistant synthesis within 120s")
                .isTrue();

        long arthurChildCountAfter = mongo.getCollection("think_processes")
                .countDocuments(Filters.eq("parentProcessId", arthurId));
        assertThat(arthurChildCountAfter)
                .as("Arthur should have at least one new child process from this turn")
                .isGreaterThan(arthurChildCountBefore);
    }

    private Document findOne(String collection, org.bson.conversions.Bson filter) {
        return mongo.getCollection(collection).find(filter).first();
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
