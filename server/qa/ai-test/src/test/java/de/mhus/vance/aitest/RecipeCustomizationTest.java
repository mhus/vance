package de.mhus.vance.aitest;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.model.Filters;
import de.mhus.vance.brain.recipe.RecipeLoader;
import de.mhus.vance.brain.recipe.RecipeSource;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * End-to-end test for the recipe customization surface. Three ordered
 * phases on the same Spring + Mongo + foot lifecycle:
 *
 * <ol>
 *   <li>{@link #step1_overrideBundledRecipe} — write a recipe document
 *       at {@code _vance/recipes/code-read.yaml} (tenant-wide override)
 *       and verify {@link RecipeLoader} resolves it from the
 *       {@code VANCE} cascade tier instead of the bundled
 *       {@code RESOURCE} layer.</li>
 *   <li>{@link #step2_brandNewRecipeViaArthur} — define a brand-new
 *       recipe {@code plumper-master} whose {@code promptPrefix} forces
 *       the worker to emit the marker word {@code BLITZSCHLAG}. Drive
 *       Arthur via {@code foot.chat} to spawn that recipe; the worker's
 *       ASSISTANT chat message must carry the marker, proving the
 *       prefix actually reached the LLM call.</li>
 *   <li>{@link #step3_recipeWithManualPaths} — define {@code css-rules/rules.md}
 *       at the tenant level plus a recipe {@code css-designer} with
 *       {@code params.manualPaths: [css-rules/]} and a prompt that
 *       insists the worker reads the rules first. The rules document
 *       carries the marker {@code CITRUS-FRESH-42} and instructs the
 *       worker to copy it into the answer; the worker's chat reply must
 *       contain it, proving {@code manual_read} resolved through the
 *       cascade.</li>
 * </ol>
 *
 * <p>Phases 2 and 3 are LLM-driven (Gemini Flash via the alias
 * resolver) and therefore inherit the same flake risk as the other
 * Arthur tests in this module — if the model paraphrases the marker
 * away or never spawns the requested recipe, the assertion fails on
 * purpose, surfacing a recipe-tuning issue rather than masking it.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RecipeCustomizationTest extends AbstractAiTest {

    private static final String PROJECT_ID = "instant-hole";

    private static final String OVERRIDE_DESCRIPTION_MARKER =
            "OVERRIDE-CODE-READ-FROM-PROJECT-VANCE";
    private static final String PLUMPER_MARKER = "BLITZSCHLAG";
    private static final String CSS_MARKER = "CITRUS-FRESH-42";

    @Autowired
    private RecipeLoader recipeLoader;

    private final ObjectMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private BrainAuthClient auth;

    /** Set in step1, reused in step2/3. */
    private String arthurId;

    // ──────────────────────────────────────────────────────────────────
    //  Phase 1 — override a bundled recipe
    //   We drop a project-level document at _vance/recipes/code-read.yaml
    //   with a description carrying a unique marker. The cascade-aware
    //   RecipeLoader must now serve it from the VANCE tier (not RESOURCE).
    // ──────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void step1_overrideBundledRecipe() throws Exception {
        auth = new BrainAuthClient("http://localhost:18080", TENANT, "wile.coyote", "acme-rocket");
        auth.mint();

        // Sanity: before override, the bundled classpath copy answers.
        Optional<ResolvedRecipe> bundled = recipeLoader.load(TENANT, PROJECT_ID, "code-read");
        assertThat(bundled)
                .as("bundled code-read must exist before the override")
                .isPresent();
        assertThat(bundled.get().source())
                .as("pre-override source must be the classpath fallback")
                .isEqualTo(RecipeSource.RESOURCE);
        assertThat(bundled.get().description())
                .as("bundled description must NOT carry the override marker yet")
                .doesNotContain(OVERRIDE_DESCRIPTION_MARKER);

        // Write the override into the tenant-wide _vance project. The
        // YAML keeps the same engine + a minimal valid shape.
        String overrideYaml = """
                description: |
                  %s — tenant override of code-read. Used by RecipeCustomizationTest
                  to prove the project/_vance cascade beats the bundled classpath copy.
                engine: ford
                params:
                  model: default:fast
                  validation: true
                  maxIterations: 3
                promptPrefix: |
                  You are the OVERRIDDEN code-read worker — answer in one short line.
                tags:
                  - test
                """.formatted(OVERRIDE_DESCRIPTION_MARKER);
        createDocument("_vance", "recipes/code-read.yaml", overrideYaml, "text/yaml");

        // After override the loader must serve the VANCE tier.
        Optional<ResolvedRecipe> overridden = recipeLoader.load(TENANT, PROJECT_ID, "code-read");
        assertThat(overridden).isPresent();
        assertThat(overridden.get().source())
                .as("override must come from the _vance project, not classpath")
                .isEqualTo(RecipeSource.VANCE);
        assertThat(overridden.get().description())
                .as("override description must carry the marker")
                .contains(OVERRIDE_DESCRIPTION_MARKER);
        assertThat(overridden.get().promptPrefix())
                .as("override promptPrefix is the new one")
                .contains("OVERRIDDEN code-read worker");

        // Boot Arthur once — phases 2 and 3 reuse this session.
        arthurId = connectAndCreateSession(PROJECT_ID);
    }

    // ──────────────────────────────────────────────────────────────────
    //  Phase 2 — brand-new recipe via document, exercised by Arthur
    //   The recipe is reachable cascade-wise. Arthur is told to spawn
    //   a worker with that exact recipe; the worker's reply must
    //   contain the BLITZSCHLAG marker that the recipe's promptPrefix
    //   forces into every answer.
    // ──────────────────────────────────────────────────────────────────

    @Test
    @Order(2)
    void step2_brandNewRecipeViaArthur() throws Exception {
        String recipeYaml = """
                description: |
                  Test recipe — ALWAYS begins every answer with the
                  marker word so RecipeCustomizationTest can prove the
                  prompt prefix actually reaches the LLM.
                engine: ford
                params:
                  model: default:fast
                  validation: false
                  maxIterations: 2
                promptPrefix: |
                  You are the Plumper Master. CRITICAL RULE: every single
                  reply you produce MUST start with the literal token
                  '%s' on its own line, in capital letters, before any
                  other text. This is non-negotiable. Then answer the
                  user's question in one short sentence.
                tags:
                  - test
                """.formatted(PLUMPER_MARKER);
        createDocument("_vance", "recipes/plumper-master.yaml", recipeYaml, "text/yaml");

        // Sanity: cascade resolves the new recipe from the VANCE tier.
        Optional<ResolvedRecipe> loaded = recipeLoader.load(TENANT, PROJECT_ID, "plumper-master");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().source()).isEqualTo(RecipeSource.VANCE);

        // Arthur, please use this recipe. Phrasing the request as a
        // tiny self-contained question keeps the LLM from improvising
        // a different recipe — this is a delegation-pipeline test, not
        // a recipe-selection-intelligence test.
        FootProcess.InputResult chat = foot.chat(
                "Spawn a worker using the recipe 'plumper-master' and ask it: "
                        + "'In one short sentence, what is two plus two?'. "
                        + "Then relay the worker's answer back to me verbatim.");
        assertThat(chat.kind()).isEqualTo("CHAT");

        Document plumperWorker = pollForOne(
                "think_processes",
                Filters.and(
                        Filters.eq("tenantId", TENANT),
                        Filters.eq("recipeName", "plumper-master"),
                        Filters.eq("parentProcessId", arthurId)),
                Duration.ofSeconds(150));
        assertThat(plumperWorker)
                .as("Arthur should spawn a plumper-master worker within 150s — "
                        + "see " + foot.workdir().resolve("foot.log") + " on failure")
                .isNotNull();

        String workerId = plumperWorker.getObjectId("_id").toHexString();

        boolean markerLanded = pollUntil(Duration.ofSeconds(150), () -> {
            List<Document> msgs = findAll("chat_messages",
                    Filters.and(
                            Filters.eq("thinkProcessId", workerId),
                            Filters.eq("role", "ASSISTANT")));
            return msgs.stream().anyMatch(m -> {
                String text = m.getString("content");
                return text != null && text.toUpperCase(Locale.ROOT).contains(PLUMPER_MARKER);
            });
        });
        assertThat(markerLanded)
                .as("plumper-master worker should emit the '%s' marker in an "
                        + "ASSISTANT message — recipe promptPrefix did not reach the LLM",
                        PLUMPER_MARKER)
                .isTrue();
    }

    // ──────────────────────────────────────────────────────────────────
    //  Phase 3 — recipe references a manual document via manualPaths
    //   The worker must call manual_read to fetch the css-rules manual
    //   and copy the marker token from it. Proves the
    //   recipe.params.manualPaths → manual_read → DocumentService
    //   cascade end-to-end.
    // ──────────────────────────────────────────────────────────────────

    @Test
    @Order(3)
    void step3_recipeWithManualPaths() throws Exception {
        // The manual itself. ManualReadTool resolves
        // <folder>/<name>.md via the document cascade — so a doc at
        // _vance/css-rules/rules.md is reachable from any project.
        String manualMarkdown = """
                # CSS Style Rules

                STRICT RULES for any HTML/CSS you produce:
                - Use only Tailwind utility classes.
                - Never write inline styles or hand-written CSS.
                - Every code answer MUST include the literal token
                  `%s` somewhere in the body so the user can verify
                  the manual was actually read.
                """.formatted(CSS_MARKER);
        createDocument("_vance", "css-rules/rules.md", manualMarkdown, "text/markdown");

        // The recipe wires manualPaths to that folder and tells the
        // worker to read it before answering.
        String recipeYaml = """
                description: |
                  CSS designer worker — must consult the css-rules
                  manual before producing any output. Used by
                  RecipeCustomizationTest to verify the manualPaths
                  → manual_read → document-cascade pipeline.
                engine: ford
                params:
                  model: default:fast
                  validation: false
                  maxIterations: 4
                  manualPaths:
                    - css-rules/
                promptPrefix: |
                  You are a CSS designer. BEFORE you write any answer,
                  you MUST call the tool `manual_read` with name='rules'
                  to load the CSS rules. Then follow them to the letter
                  in your reply, including any literal tokens the manual
                  asks you to include.
                tags:
                  - test
                """;
        createDocument("_vance", "recipes/css-designer.yaml", recipeYaml, "text/yaml");

        Optional<ResolvedRecipe> loaded = recipeLoader.load(TENANT, PROJECT_ID, "css-designer");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().source()).isEqualTo(RecipeSource.VANCE);
        assertThat(loaded.get().params().get("manualPaths"))
                .as("manualPaths must survive the YAML parse")
                .isEqualTo(List.of("css-rules/"));

        FootProcess.InputResult chat = foot.chat(
                "Spawn a worker using the recipe 'css-designer' and ask it: "
                        + "'Give me the className string for a primary login button.'. "
                        + "Relay the worker's answer back to me verbatim.");
        assertThat(chat.kind()).isEqualTo("CHAT");

        Document cssWorker = pollForOne(
                "think_processes",
                Filters.and(
                        Filters.eq("tenantId", TENANT),
                        Filters.eq("recipeName", "css-designer"),
                        Filters.eq("parentProcessId", arthurId)),
                Duration.ofSeconds(150));
        assertThat(cssWorker)
                .as("Arthur should spawn a css-designer worker within 150s — "
                        + "see " + foot.workdir().resolve("foot.log") + " on failure")
                .isNotNull();

        String workerId = cssWorker.getObjectId("_id").toHexString();

        boolean markerLanded = pollUntil(Duration.ofSeconds(180), () -> {
            List<Document> msgs = findAll("chat_messages",
                    Filters.and(
                            Filters.eq("thinkProcessId", workerId),
                            Filters.eq("role", "ASSISTANT")));
            return msgs.stream().anyMatch(m -> {
                String text = m.getString("content");
                return text != null && text.contains(CSS_MARKER);
            });
        });
        assertThat(markerLanded)
                .as("css-designer worker should emit the '%s' marker — proves "
                        + "manualPaths → manual_read fetched the rules document",
                        CSS_MARKER)
                .isTrue();
    }

    // ──────────────────── helpers ────────────────────

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
                .as("POST /documents projectId=%s path=%s — body: %s",
                        projectId, path, response.body())
                .isEqualTo(201);
    }
}
