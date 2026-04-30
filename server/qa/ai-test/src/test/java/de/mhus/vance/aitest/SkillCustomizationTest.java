package de.mhus.vance.aitest;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.model.Filters;
import de.mhus.vance.brain.skill.ResolvedSkill;
import de.mhus.vance.brain.skill.SkillResolver;
import de.mhus.vance.brain.skill.SkillScopeContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end test for the Skill subsystem — Phase 1 (manual activation
 * via {@code /skill}). The test:
 *
 * <ol>
 *   <li>Drops a custom skill document at
 *       {@code _vance/skills/citrus-marker/SKILL.md} via the documents
 *       REST API. The skill's prompt-extension forces the worker to
 *       prepend a unique marker token to every reply.</li>
 *   <li>Boots Arthur via the standard foot lifecycle, then spawns a
 *       Ford worker named {@code citrus-worker} directly through the
 *       {@code /process-create ford} slash command — bypassing
 *       Arthur's recipe-selection so the test stays focused on skill
 *       activation rather than spawn-routing intelligence.</li>
 *   <li>Activates the skill on the freshly-created worker via
 *       {@code /skill citrus-marker <follow-up message>} — the slash
 *       command sends a {@code PROCESS_SKILL ACTIVATE} on the active
 *       process and then a {@code PROCESS_STEER} carrying the trailing
 *       message, so the next Ford turn runs with the skill in scope.</li>
 *   <li>Polls {@code chat_messages} for an ASSISTANT reply on the
 *       worker carrying the marker — proves the
 *       {@code SkillPromptComposer} appended the prompt-extension and
 *       Ford honoured it.</li>
 * </ol>
 *
 * <p>Phase 2 ({@link #step2_autoTriggerActivation}) exercises the
 * auto-trigger path implemented by {@code SkillTriggerMatcher}: a
 * different skill carries {@code triggers: [{type: KEYWORDS, …}]} with
 * uncommon German tokens. The pre-turn hook in Ford calls
 * {@code detectAndActivate} on every steer, sees ≥ 50 % of the
 * configured keywords in the user input, and one-shot-activates the
 * skill before the LLM call. The worker reply must carry a separate
 * marker that only fires when this skill's prompt-extension is in
 * scope — proving the trigger detector reached the LLM.
 *
 * <p>Phase 3 ({@link #step3_cascadeOverrideOfBundledSkill}) verifies
 * the document cascade priority: the bundled {@code decision-frame}
 * skill ships on the classpath ({@code RESOURCE} tier). A document
 * dropped at {@code _vance/skills/decision-frame/SKILL.md} must beat
 * the bundled copy; {@code SkillResolver.resolve} returns the
 * {@code VANCE} tier with our override marker.
 *
 * <p>Phase 4 ({@link #step4_disabledSkillIsBlocked}) checks that
 * {@code enabled: false} prunes the skill from every consumer — the
 * cascade-resolver returns empty, manual {@code /skill} adds nothing
 * to {@code activeSkills}, and the keyword that should auto-trigger
 * leaves no marker in the worker's reply.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SkillCustomizationTest extends AbstractAiTest {

    private static final String PROJECT_ID = "instant-hole";

    // Phase 1: manual skill activation
    private static final String MANUAL_SKILL = "citrus-marker";
    private static final String MANUAL_WORKER = "citrus-worker";
    private static final String MANUAL_MARKER = "CITRUS-FRESH-42";

    // Phase 2: auto-trigger via uncommon keywords
    private static final String TRIGGER_SKILL = "zappelhuhn-mode";
    private static final String TRIGGER_WORKER = "zappelhuhn-worker";
    private static final String TRIGGER_MARKER = "ZAPPELHUHN-99";

    // Phase 3: cascade override of a bundled skill (decision-frame
    // ships under vance-defaults/skills/ on the classpath).
    private static final String OVERRIDE_SKILL = "decision-frame";
    private static final String OVERRIDE_DESCRIPTION_MARKER =
            "OVERRIDE-DECISION-FRAME-FROM-VANCE-PROJECT";

    // Phase 4: disabled skill is filtered everywhere.
    private static final String DISABLED_SKILL = "inert-marker";
    private static final String DISABLED_KEYWORD = "klabauterstein";
    private static final String DISABLED_WORKER = "inert-worker";
    private static final String DISABLED_MARKER = "INERT-MARKER-33";

    @Autowired
    private SkillResolver skillResolver;

    private final ObjectMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private BrainAuthClient auth;

    @Test
    @Order(1)
    void step1_manualSkillActivation() throws Exception {
        auth = new BrainAuthClient(
                "http://localhost:18080", TENANT, "wile.coyote", "acme-rocket");
        auth.mint();

        // ── 1. Skill document at _vance/skills/citrus-marker/SKILL.md
        // The prompt-extension carries the marker rule. We deliberately
        // also include a triggers block so the document is a realistic
        // shape, even though the auto-trigger code-path is dormant — the
        // loader must still parse it.
        String skillMarkdown = """
                ---
                name: citrus-marker
                title: Citrus Marker (Test)
                version: 1.0.0
                description: Test skill — forces the worker to prepend a marker token.
                tags: [test, marker]
                enabled: true
                triggers:
                  - type: KEYWORDS
                    keywords: [citrus, marker, fresh]
                ---

                You are operating in **citrus-marker mode**. CRITICAL RULE:
                every single reply you produce MUST start with the literal
                token `%s` on its own line, in capital letters, before any
                other text. This is non-negotiable. Then answer the user's
                question in one short sentence.
                """.formatted(MANUAL_MARKER);
        createSkillDocument(MANUAL_SKILL, skillMarkdown);

        // Sanity: cascade resolves the skill from the VANCE tier.
        SkillScopeContext scope = SkillScopeContext.of(TENANT, "wile.coyote", PROJECT_ID);
        Optional<ResolvedSkill> loaded = skillResolver.resolve(scope, MANUAL_SKILL);
        assertThat(loaded)
                .as("skill must resolve from the document we just wrote")
                .isPresent();
        assertThat(loaded.get().promptExtension())
                .as("prompt extension must carry the marker rule")
                .contains(MANUAL_MARKER);
        assertThat(loaded.get().triggers())
                .as("triggers must be parsed")
                .hasSize(1);

        // ── 2. Boot Arthur, then spawn a Ford worker directly.
        connectAndCreateSession(PROJECT_ID);
        FootProcess.CommandResult create =
                foot.command("/process-create ford " + MANUAL_WORKER);
        assertThat(create.matched())
                .as("/process-create ford %s should match", MANUAL_WORKER)
                .isTrue();

        Document workerProcess = pollForOne(
                "think_processes",
                Filters.and(
                        Filters.eq("tenantId", TENANT),
                        Filters.eq("name", MANUAL_WORKER),
                        Filters.eq("thinkEngine", "ford")),
                Duration.ofSeconds(10));
        assertThat(workerProcess)
                .as("Ford worker '%s' should appear in think_processes within 10s",
                        MANUAL_WORKER)
                .isNotNull();
        String workerId = workerProcess.getObjectId("_id").toHexString();

        // /process-create sets the new worker as the active process —
        // /skill therefore targets the worker, not Arthur. (The foot's
        // /debug/state does not expose active process state, so we
        // verify activation indirectly by polling activeSkills below.)

        // ── 3. /skill citrus-marker + trailing message → activates skill
        // on the worker and steers it with the trailing chat input. The
        // user input deliberately avoids the skill's own keywords
        // (citrus/marker/fresh) so the auto-trigger path can't claim
        // credit — only the explicit /skill call should activate it.
        FootProcess.CommandResult skillActivate = foot.command(
                "/skill " + MANUAL_SKILL
                        + " In one short sentence, what is two plus two?");
        assertThat(skillActivate.matched())
                .as("/skill %s ... should match the slash router", MANUAL_SKILL)
                .isTrue();

        // Persistence: the worker should now carry the active skill.
        boolean skillPersisted = pollUntil(Duration.ofSeconds(15), () -> {
            Document updated = findOne("think_processes",
                    Filters.eq("_id", workerProcess.getObjectId("_id")));
            if (updated == null) return false;
            Object active = updated.get("activeSkills");
            if (!(active instanceof List<?> list)) return false;
            return list.stream().anyMatch(item -> {
                if (!(item instanceof Document d)) return false;
                return MANUAL_SKILL.equals(d.getString("name"));
            });
        });
        assertThat(skillPersisted)
                .as("activeSkills on worker should contain '%s' after /skill",
                        MANUAL_SKILL)
                .isTrue();

        // ── 4. Worker reply must carry the marker.
        boolean markerLanded = pollUntil(Duration.ofSeconds(150), () -> {
            List<Document> msgs = findAll("chat_messages",
                    Filters.and(
                            Filters.eq("thinkProcessId", workerId),
                            Filters.eq("role", "ASSISTANT")));
            return msgs.stream().anyMatch(m -> {
                String text = m.getString("content");
                return text != null && text.contains(MANUAL_MARKER);
            });
        });
        assertThat(markerLanded)
                .as("Ford worker should emit '%s' in an ASSISTANT message — "
                        + "skill prompt-extension did not reach the LLM. "
                        + "Foot log: %s",
                        MANUAL_MARKER, foot.workdir().resolve("foot.log"))
                .isTrue();
    }

    // ──────────────────────────────────────────────────────────────────
    //  Phase 2 — auto-trigger via KEYWORDS
    //   A different skill carries triggers with two uncommon German
    //   tokens. We send a chat to a fresh Ford worker whose user-input
    //   contains BOTH keywords (well above the 50 % threshold). Ford's
    //   pre-turn hook calls SkillTriggerMatcher.detectAndActivate, which
    //   one-shot-activates the skill BEFORE the LLM call. The worker
    //   reply must carry the trigger-skill marker, proving the
    //   prompt-extension reached the LLM via the trigger path — not
    //   via /skill.
    // ──────────────────────────────────────────────────────────────────

    @Test
    @Order(2)
    void step2_autoTriggerActivation() throws Exception {
        // ── 1. Skill with KEYWORDS triggers. The prompt-extension wires
        // a unique marker so this phase can't be confused with phase 1.
        // The keywords are fictional German words to keep the LLM from
        // accidentally tripping the trigger via normal English chatter.
        String skillMarkdown = """
                ---
                name: zappelhuhn-mode
                title: Zappelhuhn Mode (Test)
                version: 1.0.0
                description: Test skill — fires on Zappelhuhn keywords and forces a marker.
                tags: [test, trigger]
                enabled: true
                triggers:
                  - type: KEYWORDS
                    keywords: [zappelhuhn, schwirrgeist]
                ---

                You are operating in **zappelhuhn mode**. CRITICAL RULE:
                every single reply you produce MUST start with the literal
                token `%s` on its own line, in capital letters, before any
                other text. This is non-negotiable. Then answer the user's
                question in one short sentence.
                """.formatted(TRIGGER_MARKER);
        createSkillDocument(TRIGGER_SKILL, skillMarkdown);

        // Sanity: cascade resolves the skill with parsed triggers.
        SkillScopeContext scope = SkillScopeContext.of(TENANT, "wile.coyote", PROJECT_ID);
        Optional<ResolvedSkill> loaded = skillResolver.resolve(scope, TRIGGER_SKILL);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().triggers()).hasSize(1);
        assertThat(loaded.get().triggers().get(0).keywords())
                .containsExactlyInAnyOrder("zappelhuhn", "schwirrgeist");

        // ── 2. Fresh Ford worker, NOT activating the skill manually.
        FootProcess.CommandResult create =
                foot.command("/process-create ford " + TRIGGER_WORKER);
        assertThat(create.matched()).isTrue();

        Document workerProcess = pollForOne(
                "think_processes",
                Filters.and(
                        Filters.eq("tenantId", TENANT),
                        Filters.eq("name", TRIGGER_WORKER),
                        Filters.eq("thinkEngine", "ford")),
                Duration.ofSeconds(10));
        assertThat(workerProcess).isNotNull();
        String workerId = workerProcess.getObjectId("_id").toHexString();

        // Pre-condition: nothing in activeSkills yet — the only path the
        // skill can show up is the auto-trigger.
        Object pre = workerProcess.get("activeSkills");
        if (pre instanceof List<?> list) {
            assertThat(list)
                    .as("worker must start with no zappelhuhn-mode pre-activated")
                    .noneMatch(item -> item instanceof Document d
                            && TRIGGER_SKILL.equals(d.getString("name")));
        }

        // ── 3. Plain chat to the active worker (foot.chat → PROCESS_STEER
        // on the active process). Both keywords present → 100 % match,
        // well above the 50 % threshold in SkillTriggerMatcher.
        FootProcess.InputResult chat = foot.chat(
                "Bitte gib eine ganz kurze Notiz zu Zappelhuhn und "
                        + "Schwirrgeist in einem Satz.");
        assertThat(chat.kind()).isEqualTo("CHAT");

        // ── 4. Worker reply must carry the trigger-marker.
        //   This is the end-to-end proof: the marker only lands in the
        //   reply when SkillTriggerMatcher fired pre-turn AND the
        //   skill's prompt-extension was composed into the system
        //   prompt AND the LLM honoured it. We deliberately DON'T poll
        //   activeSkills here — Ford's dropOneShotSkills() (Ford.java)
        //   runs right after the turn and removes the entry, so a
        //   post-turn snapshot is racy. The marker is the contract.
        boolean markerLanded = pollUntil(Duration.ofSeconds(150), () -> {
            List<Document> msgs = findAll("chat_messages",
                    Filters.and(
                            Filters.eq("thinkProcessId", workerId),
                            Filters.eq("role", "ASSISTANT")));
            return msgs.stream().anyMatch(m -> {
                String text = m.getString("content");
                return text != null && text.contains(TRIGGER_MARKER);
            });
        });
        assertThat(markerLanded)
                .as("Ford worker should emit '%s' in an ASSISTANT message — "
                        + "auto-triggered skill prompt-extension didn't reach "
                        + "the LLM. Foot log: %s",
                        TRIGGER_MARKER, foot.workdir().resolve("foot.log"))
                .isTrue();
    }

    // ──────────────────────────────────────────────────────────────────
    //  Phase 3 — cascade override: VANCE tier beats bundled RESOURCE.
    //   The bundled `decision-frame` ships under
    //   vance-defaults/skills/. Pre-condition is RESOURCE; after
    //   posting an override document at _vance/skills/decision-frame/
    //   SKILL.md, the cascade resolver must serve from VANCE.
    // ──────────────────────────────────────────────────────────────────

    @Test
    @Order(3)
    void step3_cascadeOverrideOfBundledSkill() throws Exception {
        SkillScopeContext scope = SkillScopeContext.of(TENANT, "wile.coyote", PROJECT_ID);

        // Pre-condition: bundled copy answers from RESOURCE tier.
        Optional<ResolvedSkill> bundled = skillResolver.resolve(scope, OVERRIDE_SKILL);
        assertThat(bundled)
                .as("bundled '%s' must exist before the override", OVERRIDE_SKILL)
                .isPresent();
        assertThat(bundled.get().source())
                .as("pre-override source must be the classpath fallback")
                .isEqualTo(de.mhus.vance.api.skills.SkillScope.RESOURCE);
        assertThat(bundled.get().description())
                .doesNotContain(OVERRIDE_DESCRIPTION_MARKER);

        // Drop the override into _vance/skills/decision-frame/SKILL.md.
        // Same name (folder name = skill stem), distinct
        // promptExtension and description so we can assert the
        // override took.
        String overrideMarkdown = """
                ---
                name: decision-frame
                title: Decision Framing — TENANT OVERRIDE
                version: 99.0.0
                description: %s — tenant override of the bundled decision-frame.
                tags: [test, override]
                enabled: true
                ---

                You are operating in the **OVERRIDDEN decision-frame mode**.
                Reply in one short sentence acknowledging the override.
                """.formatted(OVERRIDE_DESCRIPTION_MARKER);
        createSkillDocument(OVERRIDE_SKILL, overrideMarkdown);

        // After override, the cascade resolver must serve from VANCE.
        Optional<ResolvedSkill> overridden = skillResolver.resolve(scope, OVERRIDE_SKILL);
        assertThat(overridden).isPresent();
        assertThat(overridden.get().source())
                .as("override must come from the _vance project, not classpath")
                .isEqualTo(de.mhus.vance.api.skills.SkillScope.VANCE);
        assertThat(overridden.get().description())
                .as("override description carries the marker")
                .contains(OVERRIDE_DESCRIPTION_MARKER);
        assertThat(overridden.get().title())
                .as("override title is the new one")
                .contains("TENANT OVERRIDE");
    }

    // ──────────────────────────────────────────────────────────────────
    //  Phase 4 — `enabled: false` prunes the skill everywhere.
    //   The cascade resolver filters via .filter(ResolvedSkill::enabled),
    //   /skill goes through the resolver and surfaces UnknownSkill,
    //   and SkillTriggerMatcher uses listAvailable which itself drops
    //   disabled entries — so neither manual nor auto activation can
    //   land the marker in the worker's reply.
    // ──────────────────────────────────────────────────────────────────

    @Test
    @Order(4)
    void step4_disabledSkillIsBlocked() throws Exception {
        // Disabled skill with KEYWORDS triggers — would normally
        // auto-fire on the keyword if it were enabled.
        String disabledMarkdown = """
                ---
                name: inert-marker
                title: Inert Marker (Test, disabled)
                version: 1.0.0
                description: Disabled skill — must never activate.
                tags: [test, disabled]
                enabled: false
                triggers:
                  - type: KEYWORDS
                    keywords: [%s]
                ---

                You are operating in **inert-marker mode**. CRITICAL
                RULE: every reply MUST start with `%s`.
                """.formatted(DISABLED_KEYWORD, DISABLED_MARKER);
        createSkillDocument(DISABLED_SKILL, disabledMarkdown);

        // ── 4a. Cascade resolver filters disabled skills → empty.
        SkillScopeContext scope = SkillScopeContext.of(TENANT, "wile.coyote", PROJECT_ID);
        assertThat(skillResolver.resolve(scope, DISABLED_SKILL))
                .as("disabled skill must NOT resolve")
                .isEmpty();

        // ── 4b. Spawn a Ford worker, attempt manual activation: must
        // not land in activeSkills.
        FootProcess.CommandResult create =
                foot.command("/process-create ford " + DISABLED_WORKER);
        assertThat(create.matched()).isTrue();
        Document worker = pollForOne(
                "think_processes",
                Filters.and(
                        Filters.eq("tenantId", TENANT),
                        Filters.eq("name", DISABLED_WORKER),
                        Filters.eq("thinkEngine", "ford")),
                Duration.ofSeconds(10));
        assertThat(worker).isNotNull();
        String workerId = worker.getObjectId("_id").toHexString();

        foot.command("/skill " + DISABLED_SKILL);
        Thread.sleep(2000);
        Document afterManual = findOne("think_processes",
                Filters.eq("_id", worker.getObjectId("_id")));
        assertThat(afterManual).isNotNull();
        Object active = afterManual.get("activeSkills");
        if (active instanceof List<?> list) {
            assertThat(list)
                    .as("disabled skill must NOT appear in activeSkills after /skill")
                    .noneMatch(item -> item instanceof Document d
                            && DISABLED_SKILL.equals(d.getString("name")));
        }

        // ── 4c. Auto-trigger path must also skip the disabled skill.
        // Send a chat with the trigger keyword on the (still-active)
        // worker; wait for ASSISTANT reply; confirm the marker is
        // absent.
        long assistantBefore = mongo.getCollection("chat_messages").countDocuments(
                Filters.and(
                        Filters.eq("thinkProcessId", workerId),
                        Filters.eq("role", "ASSISTANT")));
        FootProcess.InputResult chat = foot.chat(
                "Sag mir bitte einen ganz kurzen Satz über '" + DISABLED_KEYWORD + "'.");
        assertThat(chat.kind()).isEqualTo("CHAT");

        boolean replyArrived = pollUntil(Duration.ofSeconds(150), () -> {
            long now = mongo.getCollection("chat_messages").countDocuments(
                    Filters.and(
                            Filters.eq("thinkProcessId", workerId),
                            Filters.eq("role", "ASSISTANT")));
            return now > assistantBefore;
        });
        assertThat(replyArrived)
                .as("disabled-keyword chat should still produce a reply")
                .isTrue();

        List<Document> assistants = findAll("chat_messages",
                Filters.and(
                        Filters.eq("thinkProcessId", workerId),
                        Filters.eq("role", "ASSISTANT")));
        for (Document msg : assistants) {
            String content = msg.getString("content");
            assertThat(content == null ? "" : content)
                    .as("ASSISTANT message %s must NOT carry the disabled-skill marker",
                            msg.getObjectId("_id").toHexString())
                    .doesNotContain(DISABLED_MARKER);
        }
    }

    private void createSkillDocument(String skillName, String markdown) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("path", "skills/" + skillName + "/SKILL.md");
        body.put("inlineText", markdown);
        body.put("mimeType", "text/markdown");

        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(
                                "http://localhost:18080/brain/" + TENANT
                                        + "/documents?projectId=_vance"))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + auth.token())
                        .POST(HttpRequest.BodyPublishers.ofString(
                                json.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("POST skill document — body: %s", response.body())
                .isEqualTo(201);
    }
}
