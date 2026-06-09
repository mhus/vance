package de.mhus.vance.brain.fook;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Renders the bundled {@code fook} recipe's {@code promptPrefix}
 * with realistic Pebble vars. Regression guard against
 * compile-passes-render-fails bugs in the template — the
 * {@link de.mhus.vance.brain.recipe.RecipeLoader} only invokes the
 * Pebble compiler, which validates syntax but not runtime semantics
 * (e.g. operator-type mismatches that only surface once vars are
 * bound).
 *
 * <p>Driven by the actual {@code fook.yaml} on the classpath, so a
 * recipe author who breaks the template trips this test.
 */
class FookRecipeRenderingTest {

    private static String promptPrefix;
    private static PromptTemplateRenderer renderer;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void loadRecipe() throws Exception {
        renderer = new PromptTemplateRenderer();
        try (InputStream in = FookRecipeRenderingTest.class
                .getResourceAsStream("/vance-defaults/_vance/recipes/fook.yaml")) {
            assertThat(in)
                    .as("bundled fook recipe must be on the classpath")
                    .isNotNull();
            String yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> root = (Map<String, Object>) new Yaml().load(yaml);
            promptPrefix = (String) root.get("promptPrefix");
            assertThat(promptPrefix).isNotBlank();
        }
    }

    @Test
    void renders_with_empty_candidate_list() {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("text", "Brain crashed on boot — NPE in recipes loader.");
        ctx.put("candidates", List.of());

        String out = renderer.render(promptPrefix, ctx);

        assertThat(out)
                .as("must render the no-candidates branch")
                .contains("no candidates")
                .doesNotContain("###"); // no candidate cards rendered
        assertThat(out).contains("Brain crashed on boot");
    }

    @Test
    void renders_with_populated_candidates() {
        Map<String, Object> rel = new LinkedHashMap<>();
        rel.put("duplicateOf", null);
        rel.put("rootCauseOf", List.of("uuid-rc-1"));
        rel.put("relatedTo", List.of());
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("id", "uuid-c1");
        candidate.put("type", "bug");
        candidate.put("severity", "high");
        candidate.put("status", "new");
        candidate.put("title", "Brain crash on boot");
        candidate.put("description", "Boot throws NPE.");
        candidate.put("relations", rel);

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("text", "Same brain crash.");
        ctx.put("candidates", List.of(candidate));

        String out = renderer.render(promptPrefix, ctx);

        assertThat(out)
                .contains("uuid-c1")
                .contains("Brain crash on boot")
                .contains("Boot throws NPE.")
                .contains("Root cause of:")
                .contains("uuid-rc-1")
                .doesNotContain("Duplicate of:") // duplicateOf is null
                .doesNotContain("no candidates");
    }

    @Test
    void renders_candidate_with_duplicateOf_set() {
        Map<String, Object> rel = new LinkedHashMap<>();
        rel.put("duplicateOf", "uuid-canon");
        rel.put("rootCauseOf", List.of());
        rel.put("relatedTo", List.of());
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("id", "uuid-c1");
        candidate.put("type", "bug");
        candidate.put("severity", "low");
        candidate.put("status", "new");
        candidate.put("title", "Duplicate-of report");
        candidate.put("description", "Already a known issue.");
        candidate.put("relations", rel);

        String out = renderer.render(promptPrefix,
                Map.of("text", "irrelevant",
                        "candidates", List.of(candidate)));

        assertThat(out)
                .contains("Duplicate of:")
                .contains("uuid-canon")
                .doesNotContain("Root cause of:");
    }
}
