package de.mhus.vance.aitest;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.model.Filters;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end test for the Recipe ↔ Skill composition surface
 * ({@code recipes.md} §6c, {@code skills.md} §7). Three ordered phases
 * on a single Spring context:
 *
 * <ol>
 *   <li>{@link #step1_defaultActiveSkillsSeeded} — a recipe with
 *       {@code defaultActiveSkills: [hat-marker]} seeds the spawned
 *       worker's {@code activeSkills} with one {@code fromRecipe=true}
 *       entry, and the skill's prompt-extension reaches the very first
 *       LLM turn (proven by a marker token in the worker's reply).</li>
 *   <li>{@link #step2_allowedSkillsRejectsManualActivation} — a recipe
 *       with {@code allowedSkills: [hat-marker]} (lockdown to that one
 *       name) snapshots {@code allowedSkillsOverride} on the worker and
 *       makes {@code /skill outside-skill} a no-op (the
 *       {@code SkillSteerProcessor} throws
 *       {@code SkillNotAllowedByRecipeException}); the same worker
 *       accepts {@code /skill hat-marker} because it is on the
 *       whitelist.</li>
 *   <li>{@link #step3_allowedSkillsFiltersAutoTrigger} — on the same
 *       whitelisted worker, a chat input that carries the trigger
 *       keyword for {@code outside-skill} does NOT auto-activate it:
 *       the {@code SkillTriggerMatcher} pre-turn hook filters by
 *       {@code allowedSkillsOverride} (Zeile 92) before checking
 *       triggers, so the marker that {@code outside-skill} would force
 *       never appears in the reply, and {@code brain.log} carries no
 *       {@code "Skill auto-trigger ... outside-skill"} line.</li>
 * </ol>
 *
 * <p>Spawning workers with a specific recipe is done via Arthur
 * (LLM-driven) — there is no {@code /process-create} variant in the
 * foot client that takes a recipe today, so we phrase the request as a
 * direct instruction and Arthur calls {@code process_create(recipe=…)}.
 * This is the same pattern as {@link RecipeCustomizationTest} and
 * inherits the same flake risk: the assertions are deliberate and
 * surface a recipe-tuning issue rather than mask it.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RecipeSkillCompositionTest extends AbstractAiTest {

    private static final String PROJECT_ID = "instant-hole";

    private static final String SKILL_ALLOWED = "hat-marker";
    private static final String SKILL_OUTSIDE = "outside-skill";

    private static final String RECIPE_DEFAULTS = "recipe-hat-default";
    private static final String RECIPE_LOCKED = "recipe-hat-only";

    /** Marker forced by hat-marker's prompt-extension. */
    private static final String HAT_MARKER = "HAT-MARKER-77";
    /** Marker forced by outside-skill's prompt-extension. */
    private static final String OUTSIDE_MARKER = "OUTSIDE-MARKER-55";
    /** Uncommon trigger keyword for outside-skill — almost zero false-positive risk. */
    private static final String OUTSIDE_KEYWORD = "ausserweltler";

    private final ObjectMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private BrainAuthClient auth;

    @BeforeAll
    void seedDocuments() throws Exception {
        auth = new BrainAuthClient(
                "http://localhost:18080", TENANT, "wile.coyote", "acme-rocket");
        auth.mint();

        // ── Skill 1: hat-marker (no triggers — only ever activated
        // explicitly via defaultActiveSkills or /skill).
        createDocument("_vance",
                "skills/" + SKILL_ALLOWED + "/SKILL.md",
                """
                ---
                name: hat-marker
                title: Hat Marker (Test)
                version: 1.0.0
                description: Test skill — forces a marker token.
                tags: [test, marker]
                enabled: true
                ---

                You are operating in **hat-marker mode**. CRITICAL RULE:
                every single reply you produce MUST start with the
                literal token `%s` on its own line, in capital letters,
                before any other text. Then answer in one short sentence.
                """.formatted(HAT_MARKER),
                "text/markdown");

        // ── Skill 2: outside-skill — has KEYWORDS triggers so the
        // auto-trigger path is the natural way for it to activate.
        // The whitelist test in step 3 suppresses it.
        createDocument("_vance",
                "skills/" + SKILL_OUTSIDE + "/SKILL.md",
                """
                ---
                name: outside-skill
                title: Outside Skill (Test)
                version: 1.0.0
                description: Test skill — fires on rare keyword and forces a marker.
                tags: [test, trigger]
                enabled: true
                triggers:
                  - type: KEYWORDS
                    keywords: [%s]
                ---

                You are operating in **outside-skill mode**. CRITICAL
                RULE: every single reply you produce MUST start with
                the literal token `%s` on its own line, in capital
                letters, before any other text.
                """.formatted(OUTSIDE_KEYWORD, OUTSIDE_MARKER),
                "text/markdown");

        // ── Recipe 1: defaultActiveSkills seeds hat-marker — no
        // whitelist (allowedSkillsOverride stays null, all skills
        // remain reachable via /skill or trigger).
        createDocument("_vance",
                "recipes/" + RECIPE_DEFAULTS + ".yaml",
                """
                description: |
                  Test recipe — pre-seeds the hat-marker skill via
                  defaultActiveSkills so the very first turn already
                  carries its prompt-extension.
                engine: ford
                defaultActiveSkills:
                  - hat-marker
                params:
                  model: default:fast
                  validation: false
                  maxIterations: 2
                promptPrefix: |
                  You are a brief tester worker. Answer in one short
                  sentence.
                tags: [test]
                """,
                "text/yaml");

        // ── Recipe 2: lockdown via allowedSkills. defaultActiveSkills
        // is omitted so the worker starts with an empty activeSkills
        // list — phase 2 tests manual activation against the whitelist.
        createDocument("_vance",
                "recipes/" + RECIPE_LOCKED + ".yaml",
                """
                description: |
                  Test recipe — restricts skill activation to hat-marker
                  via the allowedSkills whitelist. /skill of any other
                  name is rejected by SkillSteerProcessor; auto-trigger
                  in SkillTriggerMatcher filters the same way.
                engine: ford
                allowedSkills:
                  - hat-marker
                params:
                  model: default:fast
                  validation: false
                  maxIterations: 2
                promptPrefix: |
                  You are a brief tester worker. Answer in one short
                  sentence.
                tags: [test]
                """,
                "text/yaml");

        // Boot Arthur for the whole test class.
        connectAndCreateSession(PROJECT_ID);
    }

    // ──────────────────────────────────────────────────────────────────
    //  Phase 1 — defaultActiveSkills seeded into a freshly spawned worker
    // ──────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void step1_defaultActiveSkillsSeeded() throws Exception {
        FootProcess.InputResult chat = foot.chat(
                "Spawn a worker named 'hat-worker-default' using the recipe '"
                        + RECIPE_DEFAULTS + "' and ask it: 'In one short sentence, "
                        + "what is the capital of France?'. Relay the worker's "
                        + "answer back to me verbatim.");
        assertThat(chat.kind()).isEqualTo("CHAT");

        Document worker = pollForOne(
                "think_processes",
                Filters.and(
                        Filters.eq("tenantId", TENANT),
                        Filters.eq("name", "hat-worker-default"),
                        Filters.eq("recipeName", RECIPE_DEFAULTS)),
                Duration.ofSeconds(150));
        assertThat(worker)
                .as("Arthur should spawn 'hat-worker-default' with recipe '%s' "
                        + "within 150s — see %s on failure",
                        RECIPE_DEFAULTS, foot.workdir().resolve("foot.log"))
                .isNotNull();
        String workerId = worker.getObjectId("_id").toHexString();

        // ── 1a. activeSkills was seeded from defaultActiveSkills with
        // fromRecipe=true. This is visible immediately on spawn (before
        // the first turn even runs).
        Document seed = findActiveSkillByName(worker, SKILL_ALLOWED);
        assertThat(seed)
                .as("hat-marker must be pre-seeded in activeSkills via defaultActiveSkills")
                .isNotNull();
        assertThat(seed.getBoolean("fromRecipe"))
                .as("seeded skill must carry fromRecipe=true")
                .isTrue();
        assertThat(seed.getBoolean("oneShot"))
                .as("seeded skill is sticky, not one-shot")
                .isFalse();

        // ── 1b. allowedSkillsOverride must be absent / null because
        // recipe-hat-default carries no allowedSkills field.
        Object whitelistRaw = worker.get("allowedSkillsOverride");
        if (whitelistRaw instanceof List<?> list) {
            assertThat(list)
                    .as("recipe with no allowedSkills should leave whitelist unset")
                    .isEmpty();
        }
        // (some serialisations write null, some omit — both are fine here)

        // ── 1c. End-to-end: the seeded skill's prompt-extension reaches
        // the LLM, so the worker reply carries the marker.
        boolean markerLanded = pollUntil(Duration.ofSeconds(150), () -> {
            List<Document> msgs = findAll("chat_messages",
                    Filters.and(
                            Filters.eq("thinkProcessId", workerId),
                            Filters.eq("role", "ASSISTANT")));
            return msgs.stream().anyMatch(m -> {
                String text = m.getString("content");
                return text != null && text.contains(HAT_MARKER);
            });
        });
        assertThat(markerLanded)
                .as("worker should emit '%s' — defaultActiveSkills did not "
                        + "reach the LLM. Foot log: %s",
                        HAT_MARKER, foot.workdir().resolve("foot.log"))
                .isTrue();
    }

    // ──────────────────────────────────────────────────────────────────
    //  Phase 2 — allowedSkills rejects /skill outside the whitelist
    // ──────────────────────────────────────────────────────────────────

    @Test
    @Order(2)
    void step2_allowedSkillsRejectsManualActivation() throws Exception {
        // Spawn the locked worker via Arthur. We instruct it to NOT ask
        // the worker anything — we want to drive activation ourselves.
        FootProcess.InputResult chat = foot.chat(
                "Spawn a worker named 'hat-worker-locked' using the recipe '"
                        + RECIPE_LOCKED + "'. Do not ask the worker any "
                        + "question — just create it and tell me when it is "
                        + "ready.");
        assertThat(chat.kind()).isEqualTo("CHAT");

        Document worker = pollForOne(
                "think_processes",
                Filters.and(
                        Filters.eq("tenantId", TENANT),
                        Filters.eq("name", "hat-worker-locked"),
                        Filters.eq("recipeName", RECIPE_LOCKED)),
                Duration.ofSeconds(150));
        assertThat(worker)
                .as("Arthur should spawn 'hat-worker-locked' within 150s")
                .isNotNull();

        // ── 2a. allowedSkillsOverride is the snapshot of the recipe's
        // allowedSkills list, persisted at spawn time.
        Object whitelistRaw = worker.get("allowedSkillsOverride");
        assertThat(whitelistRaw)
                .as("locked recipe must persist allowedSkillsOverride")
                .isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> whitelist = (List<String>) whitelistRaw;
        assertThat(whitelist)
                .as("whitelist must contain only the recipe-allowed skill")
                .containsExactlyInAnyOrder(SKILL_ALLOWED);

        // ── 2b. /skill outside-skill must be rejected. The slash
        // command catches the WS error envelope and prints to the
        // terminal; the foot's CommandService swallows the exception
        // and returns matched=true, so we verify the rejection by
        // confirming activeSkills was NOT mutated.
        foot.command("/process hat-worker-locked");
        foot.command("/skill " + SKILL_OUTSIDE);

        // The activate fast-paths through SkillSteerProcessor before
        // any persistence happens — the absence is observable
        // immediately, but a tiny grace window lets the WS round-trip
        // settle.
        Thread.sleep(2000);
        Document workerPostReject = findOne("think_processes",
                Filters.eq("_id", worker.getObjectId("_id")));
        assertThat(workerPostReject).isNotNull();
        assertThat(findActiveSkillByName(workerPostReject, SKILL_OUTSIDE))
                .as("outside-skill must NOT appear in activeSkills — "
                        + "allowedSkills whitelist did not block /skill")
                .isNull();

        // ── 2c. /skill hat-marker (in whitelist) succeeds.
        foot.command("/skill " + SKILL_ALLOWED);

        boolean activated = pollUntil(Duration.ofSeconds(15), () -> {
            Document u = findOne("think_processes",
                    Filters.eq("_id", worker.getObjectId("_id")));
            return u != null && findActiveSkillByName(u, SKILL_ALLOWED) != null;
        });
        assertThat(activated)
                .as("hat-marker must activate — it is on the whitelist")
                .isTrue();
    }

    // ──────────────────────────────────────────────────────────────────
    //  Phase 3 — allowedSkills filters auto-trigger detection
    // ──────────────────────────────────────────────────────────────────

    @Test
    @Order(3)
    void step3_allowedSkillsFiltersAutoTrigger() throws Exception {
        // Re-use the locked worker from phase 2 — it still has
        // allowedSkillsOverride=[hat-marker]. Sending a chat with the
        // outside-skill keyword would normally fire the auto-trigger,
        // but the whitelist filter in SkillTriggerMatcher (line 92)
        // skips outside-skill before triggers are even tested.
        Document worker = findOne("think_processes",
                Filters.and(
                        Filters.eq("tenantId", TENANT),
                        Filters.eq("name", "hat-worker-locked")));
        assertThat(worker).as("Phase 2 worker must still exist").isNotNull();
        String workerId = worker.getObjectId("_id").toHexString();

        // Snapshot brain.log size BEFORE the chat so the post-condition
        // scan only looks at lines this phase produced.
        Path brainLog = Path.of("target", "ai-test", "brain.log").toAbsolutePath();
        long brainLogOffset = Files.exists(brainLog) ? Files.size(brainLog) : 0L;

        // Snapshot ASSISTANT-message count BEFORE so we can wait for a
        // FRESH reply rather than picking up a stale one.
        long assistantBefore = mongo.getCollection("chat_messages").countDocuments(
                Filters.and(
                        Filters.eq("thinkProcessId", workerId),
                        Filters.eq("role", "ASSISTANT")));

        // /process is still hat-worker-locked (set in phase 2). Plain
        // chat goes to it as PROCESS_STEER. The keyword forces a
        // 100 % KEYWORDS match if outside-skill were considered.
        FootProcess.InputResult chat = foot.chat(
                "Sag mir bitte einen ganz kurzen Satz über '" + OUTSIDE_KEYWORD + "'.");
        assertThat(chat.kind()).isEqualTo("CHAT");

        // Wait for a fresh ASSISTANT reply (one beyond the snapshot).
        boolean replyArrived = pollUntil(Duration.ofSeconds(150), () -> {
            long now = mongo.getCollection("chat_messages").countDocuments(
                    Filters.and(
                            Filters.eq("thinkProcessId", workerId),
                            Filters.eq("role", "ASSISTANT")));
            return now > assistantBefore;
        });
        assertThat(replyArrived)
                .as("locked worker should still produce a reply within 150s")
                .isTrue();

        // ── 3a. The outside-skill marker must NOT appear — its
        // prompt-extension never reached the LLM because the whitelist
        // filtered it out before trigger evaluation.
        List<Document> assistants = findAll("chat_messages",
                Filters.and(
                        Filters.eq("thinkProcessId", workerId),
                        Filters.eq("role", "ASSISTANT")));
        for (Document msg : assistants) {
            String content = msg.getString("content");
            assertThat(content == null ? "" : content)
                    .as("ASSISTANT message %s on locked worker must NOT carry the "
                            + "outside-skill marker — whitelist filter is broken",
                            msg.getObjectId("_id").toHexString())
                    .doesNotContain(OUTSIDE_MARKER);
        }

        // ── 3b. brain.log must NOT carry an auto-trigger line for
        // outside-skill within this phase's window — the matcher
        // shouldn't have even considered the skill.
        if (Files.exists(brainLog)) {
            String tail = readFromOffset(brainLog, brainLogOffset);
            assertThat(tail)
                    .as("brain.log must NOT carry 'Skill auto-trigger ... %s' since "
                            + "the whitelist filtered it out before trigger eval",
                            SKILL_OUTSIDE)
                    .doesNotContain("Skill auto-trigger")
                    // belt-and-braces: name match too in case log format changes
                    .doesNotContain(SKILL_OUTSIDE + "' (one-shot)");
        }
    }

    // ──────────────────── helpers ────────────────────

    /** Returns the {@code activeSkills} entry for {@code name} or null. */
    private static Document findActiveSkillByName(Document process, String name) {
        Object active = process.get("activeSkills");
        if (!(active instanceof List<?> list)) return null;
        for (Object item : list) {
            if (item instanceof Document d && name.equals(d.getString("name"))) {
                return d;
            }
        }
        return null;
    }

    private void createDocument(
            String projectId, String path, String inlineText, String mimeType)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("path", path);
        body.put("inlineText", inlineText);
        body.put("mimeType", mimeType);

        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(
                                "http://localhost:18080/brain/" + TENANT
                                        + "/documents?projectId=" + projectId))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + auth.token())
                        .POST(HttpRequest.BodyPublishers.ofString(
                                json.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("POST %s %s — body: %s", projectId, path, response.body())
                .isEqualTo(201);
    }

    private static String readFromOffset(Path file, long offset) throws Exception {
        try (var ch = java.nio.channels.FileChannel.open(file,
                java.nio.file.StandardOpenOption.READ)) {
            long size = ch.size();
            if (offset >= size) return "";
            ch.position(offset);
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate((int) (size - offset));
            while (buf.hasRemaining() && ch.read(buf) > 0) {
                // loop
            }
            return new String(buf.array(), 0, buf.position(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
