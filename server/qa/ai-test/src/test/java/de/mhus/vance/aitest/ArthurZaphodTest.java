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
 * Arthur + Zaphod end-to-end test using the BMAD-METHOD-style
 * council recipe ({@code council-bmad-build}: PM / Architect / Dev
 * heads + synthesis). The user asks Arthur to convene the council
 * for a software-build question; Arthur picks the recipe and spawns
 * Zaphod, which sequentially drives three Ford-heads and finally
 * runs a synthesizer LLM call.
 *
 * <p>Asserted shape:
 * <ul>
 *   <li>Arthur runs in {@code instant-hole} (NORMAL project).</li>
 *   <li>A Zaphod-process exists with {@code parentProcessId = arthur.id}
 *       and {@code thinkEngine = zaphod}.</li>
 *   <li>Zaphod's {@code engineParams.zaphodState.heads} carries the 3
 *       BMAD heads (pm, architect, dev).</li>
 *   <li>At least one head sub-process was spawned with
 *       {@code parentProcessId = zaphod.id}.</li>
 *   <li>Eventually all heads reach status {@code done} and Zaphod
 *       writes a non-empty {@code synthesis} into its state.</li>
 * </ul>
 *
 * <p>Each Ford head is one LLM round-trip plus the synthesizer is
 * another, so the test gives a 4-minute budget for the full council.
 */
@SpringBootTest(
        classes = VanceBrainApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("aitest")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ArthurZaphodTest {

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
    void arthurDelegatesToBmadCouncil() throws Exception {
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

        // Direct ask for the BMAD-style council recipe. Phrasing makes
        // it hard for the LLM to pick anything else.
        FootProcess.InputResult chat = foot.chat(
                "Ich brauche eine BMAD-orientierte Multi-Sicht-Beratung "
                        + "(PM / Architect / Dev) für folgendes Vorhaben: "
                        + "'Wir wollen eine API-Rate-Limiting-Schicht einführen, "
                        + "die per User konfigurierbar ist'. Bitte spawne "
                        + "explizit das Recipe `council-bmad-build` und "
                        + "übergib das Vorhaben als `goal`.");
        assertThat(chat.ok())
                .as("/debug/chat should succeed; error: %s", chat.error())
                .isTrue();

        // ─── Zaphod-process should appear under Arthur.
        boolean zaphodSpawned = pollUntil(Duration.ofSeconds(120), () -> {
            long count = mongo.getCollection("think_processes")
                    .countDocuments(Filters.and(
                            Filters.eq("parentProcessId", arthurId),
                            Filters.eq("thinkEngine", "zaphod")));
            return count >= 1;
        });
        assertThat(zaphodSpawned)
                .as("Arthur should spawn a Zaphod-process within 120s. "
                        + "If false: check brain.log for 'tool_use process_create' with engine=zaphod or recipe=council-bmad-build.")
                .isTrue();

        Document zaphod = mongo.getCollection("think_processes")
                .find(Filters.and(
                        Filters.eq("parentProcessId", arthurId),
                        Filters.eq("thinkEngine", "zaphod")))
                .sort(Sorts.descending("_id"))
                .first();
        assertThat(zaphod).isNotNull();
        String zaphodId = zaphod.getObjectId("_id").toHexString();

        // ─── Zaphod's state must carry the 3 BMAD heads.
        boolean stateInitialised = pollUntil(Duration.ofSeconds(30), () -> {
            Document fresh = findOne("think_processes",
                    Filters.eq("_id", zaphod.getObjectId("_id")));
            if (fresh == null) return false;
            Object epRaw = fresh.get("engineParams");
            if (!(epRaw instanceof Map)) return false;
            Object stateRaw = ((Map<?, ?>) epRaw).get("zaphodState");
            if (!(stateRaw instanceof Map)) return false;
            Object headsRaw = ((Map<?, ?>) stateRaw).get("heads");
            return headsRaw instanceof java.util.List<?> list && list.size() == 3;
        });
        assertThat(stateInitialised)
                .as("Zaphod state should carry the 3 BMAD heads (pm/architect/dev) within 30s")
                .isTrue();

        // ─── At least one head sub-process should be spawned.
        boolean someHeadSpawned = pollUntil(Duration.ofSeconds(120), () -> {
            long count = mongo.getCollection("think_processes")
                    .countDocuments(Filters.eq("parentProcessId", zaphodId));
            return count >= 1;
        });
        assertThat(someHeadSpawned)
                .as("At least one Zaphod head sub-process should be spawned within 120s")
                .isTrue();

        // ─── Eventually Zaphod completes the synthesis. Generous timeout
        //     because three Ford heads + one synthesizer call run sequentially.
        boolean synthesised = pollUntil(Duration.ofSeconds(240), () -> {
            Document fresh = findOne("think_processes",
                    Filters.eq("_id", zaphod.getObjectId("_id")));
            if (fresh == null) return false;
            Object epRaw = fresh.get("engineParams");
            if (!(epRaw instanceof Map)) return false;
            Object stateRaw = ((Map<?, ?>) epRaw).get("zaphodState");
            if (!(stateRaw instanceof Map)) return false;
            Map<?, ?> state = (Map<?, ?>) stateRaw;
            Object synthesis = state.get("synthesis");
            return synthesis instanceof String s && !s.isBlank();
        });
        assertThat(synthesised)
                .as("Zaphod should produce a non-empty synthesis within 240s. "
                        + "Inspect zaphodState.heads[*].status and synthesis in Mongo if false.")
                .isTrue();

        // ─── Sanity: the synthesis really mentions BMAD-shaped sections.
        Document fresh = findOne("think_processes",
                Filters.eq("_id", zaphod.getObjectId("_id")));
        @SuppressWarnings("unchecked")
        Map<String, Object> ep = (Map<String, Object>) fresh.get("engineParams");
        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) ep.get("zaphodState");
        String synthesis = (String) state.get("synthesis");
        assertThat(synthesis)
                .as("Synthesis should mention at least one BMAD-shaped section "
                        + "(Story / Architecture / Implementation)")
                .containsAnyOf("Story", "Architecture", "Implementation");
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
