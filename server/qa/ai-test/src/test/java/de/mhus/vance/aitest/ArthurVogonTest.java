package de.mhus.vance.aitest;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import de.mhus.vance.brain.VanceBrainApplication;
import java.time.Duration;
import java.util.Map;
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
 * Arthur + Vogon end-to-end test. The user asks Arthur for a
 * structured multi-phase deliverable. Arthur picks the
 * {@code waterfall-feature} recipe and spawns a Vogon-process that
 * runs the {@code waterfall} strategy. Vogon then sequentially
 * spawns one Ford-worker per phase (planning/implementation/review)
 * and pauses at approval-checkpoints by creating inbox items.
 *
 * <p>Asserted shape:
 * <ul>
 *   <li>Arthur is the chat-process (instant-hole = NORMAL project).</li>
 *   <li>A Vogon-process exists with {@code parentProcessId = arthur.id},
 *       {@code thinkEngine = vogon}, and {@code engineParams.strategyState}
 *       carrying the strategy snapshot.</li>
 *   <li>At least one phase-worker (Ford) was spawned by Vogon
 *       ({@code parentProcessId = vogon.id}).</li>
 *   <li>An {@code APPROVAL} inbox item is created for the user as a
 *       checkpoint (might race with phase progression; we accept
 *       "either approval-item exists or vogon already moved past
 *       planning").</li>
 * </ul>
 */
@SpringBootTest(
        classes = VanceBrainApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("aitest")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ArthurVogonTest {

    private static final FootProcess foot = new FootProcess();

    private static final String TENANT = "acme";
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
        assertThat(up).isTrue();
    }

    @AfterAll
    void stopFoot() {
        foot.stop();
    }

    @Test
    void arthurDelegatesToVogonStrategy() throws Exception {
        FootProcess.CommandResult connect = foot.command("/connect");
        assertThat(connect.matched()).isTrue();
        boolean opened = pollUntil(Duration.ofSeconds(30),
                () -> Boolean.TRUE.equals(foot.state().get("connectionOpen")));
        assertThat(opened).isTrue();

        FootProcess.CommandResult create = foot.command(
                "/session-create " + SEED_PROJECT);
        assertThat(create.matched()).isTrue();

        boolean arthurAppeared = pollUntil(Duration.ofSeconds(15), () -> {
            Document p = findOne("think_processes",
                    Filters.and(
                            Filters.eq("tenantId", TENANT),
                            Filters.eq("thinkEngine", "arthur")));
            return p != null;
        });
        assertThat(arthurAppeared).isTrue();

        Document arthur = mongo.getCollection("think_processes")
                .find(Filters.and(
                        Filters.eq("tenantId", TENANT),
                        Filters.eq("thinkEngine", "arthur")))
                .sort(Sorts.descending("_id"))
                .first();
        assertThat(arthur).isNotNull();
        String arthurId = arthur.getObjectId("_id").toHexString();

        // Ask for a multi-phase task. The phrasing nudges toward the
        // waterfall-feature recipe — strict phases + approval gates.
        FootProcess.InputResult chat = foot.chat(
                "Ich möchte ein neues Feature 'Logging-Dashboard' nach einem "
                        + "strikten Plan umsetzen — erst Planung, dann Implementierung, "
                        + "dann Review, mit meiner Freigabe zwischen den Phasen. "
                        + "Bitte spawne dafür das `waterfall-feature` Recipe.");
        assertThat(chat.ok())
                .as("/debug/chat should succeed; error: %s", chat.error())
                .isTrue();

        // Vogon-process should appear as Arthur's child.
        boolean vogonSpawned = pollUntil(Duration.ofSeconds(120), () -> {
            long count = mongo.getCollection("think_processes")
                    .countDocuments(Filters.and(
                            Filters.eq("parentProcessId", arthurId),
                            Filters.eq("thinkEngine", "vogon")));
            return count >= 1;
        });
        assertThat(vogonSpawned)
                .as("Arthur should spawn a Vogon-process within 120s. "
                        + "Check brain.log for 'tool_use process_create' with engine=vogon.")
                .isTrue();

        Document vogon = mongo.getCollection("think_processes")
                .find(Filters.and(
                        Filters.eq("parentProcessId", arthurId),
                        Filters.eq("thinkEngine", "vogon")))
                .sort(Sorts.descending("_id"))
                .first();
        assertThat(vogon).isNotNull();
        String vogonId = vogon.getObjectId("_id").toHexString();

        // Strategy-state snapshot must be persisted on the Vogon process.
        Object engineParamsRaw = vogon.get("engineParams");
        assertThat(engineParamsRaw)
                .as("Vogon engineParams must include the strategy snapshot")
                .isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> engineParams = (Map<String, Object>) engineParamsRaw;
        assertThat(engineParams)
                .as("engineParams should carry 'strategy' identifying the plan")
                .containsKey("strategy");

        // Vogon spawns a phase-worker (Ford) sometime after start.
        // Don't pin which phase — could be planning, or already moved on if
        // the LLM is fast and approval auto-resolved.
        boolean phaseWorkerSpawned = pollUntil(Duration.ofSeconds(120), () -> {
            long count = mongo.getCollection("think_processes")
                    .countDocuments(Filters.eq("parentProcessId", vogonId));
            return count >= 1;
        });
        assertThat(phaseWorkerSpawned)
                .as("Vogon should spawn at least one phase-worker within 120s. "
                        + "If false: check Vogon-process status and strategyState in Mongo.")
                .isTrue();

        // Cross-check: at least one of {approval-inbox-item exists,
        // strategyState.flags shows progress past planning}.
        // Inbox items have originProcessId pointing to the Vogon process.
        boolean checkpointSurfaced = pollUntil(Duration.ofSeconds(60), () -> {
            // Approval inbox-item from Vogon.
            Document inboxApproval = findOne("inbox_items",
                    Filters.and(
                            Filters.eq("tenantId", TENANT),
                            Filters.eq("originProcessId", vogonId),
                            Filters.eq("type", "APPROVAL")));
            if (inboxApproval != null) return true;

            // Or Vogon already advanced (planning_completed flag set, or
            // currentPhasePath != "planning").
            Document fresh = findOne("think_processes", Filters.eq("_id", vogon.getObjectId("_id")));
            if (fresh == null) return false;
            Object epRaw = fresh.get("engineParams");
            if (!(epRaw instanceof Map)) return false;
            Object stateRaw = ((Map<?, ?>) epRaw).get("strategyState");
            if (!(stateRaw instanceof Map)) return false;
            Map<?, ?> state = (Map<?, ?>) stateRaw;
            Object flags = state.get("flags");
            if (flags instanceof Map<?, ?> flagsMap
                    && Boolean.TRUE.equals(flagsMap.get("planning_completed"))) {
                return true;
            }
            Object currentPhase = state.get("currentPhasePath");
            return currentPhase != null && !"planning".equals(currentPhase);
        });
        assertThat(checkpointSurfaced)
                .as("Either an APPROVAL inbox item should exist for the user, "
                        + "or Vogon should have moved past planning. Check the "
                        + "vogon process's strategyState for progress.")
                .isTrue();
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
