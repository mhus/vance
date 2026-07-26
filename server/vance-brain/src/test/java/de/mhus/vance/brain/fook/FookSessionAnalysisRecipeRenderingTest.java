package de.mhus.vance.brain.fook;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Renders the bundled {@code fook-session-analysis} recipe's
 * {@code promptPrefix} with realistic Pebble vars — regression guard
 * against compile-passes-render-fails template bugs (the conditional
 * ticket-header blocks in particular). Driven by the actual
 * {@code fook-session-analysis.yaml} on the classpath.
 */
class FookSessionAnalysisRecipeRenderingTest {

    private static String promptPrefix;
    private static PromptTemplateRenderer renderer;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void loadRecipe() throws Exception {
        renderer = new PromptTemplateRenderer();
        try (InputStream in = FookSessionAnalysisRecipeRenderingTest.class
                .getResourceAsStream(
                        "/vance-defaults/_vance/recipes/fook-session-analysis.yaml")) {
            assertThat(in)
                    .as("bundled fook-session-analysis recipe must be on the classpath")
                    .isNotNull();
            String yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> root = (Map<String, Object>) new Yaml().load(yaml);
            promptPrefix = (String) root.get("promptPrefix");
            assertThat(promptPrefix).isNotBlank();
        }
    }

    @Test
    void renders_with_full_context() {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("ticketTitle", "Crash on save");
        ctx.put("ticketType", "bug");
        ctx.put("reason", "Distinct crash not seen before.");
        ctx.put("triageNote", "Repro present in session.");
        ctx.put("engine", "arthur");
        ctx.put("recipe", "coding");
        ctx.put("stepsLeft", 10);
        ctx.put("observations", "OVERVIEW: 3 messages, indices 0..2.");

        String out = renderer.render(promptPrefix, ctx);

        assertThat(out)
                .contains("Crash on save")
                .contains("Distinct crash not seen before.")
                .contains("Repro present in session.")
                .contains("arthur")
                // loop scaffolding must render
                .contains("OVERVIEW: 3 messages")
                .contains("Steps remaining: 10")
                .contains("\"action\": \"finish\"")
                .contains("\"useful\"");
    }

    @Test
    void renders_with_optional_fields_blank() {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("ticketTitle", "Feature: dark mode");
        ctx.put("ticketType", "feature");
        ctx.put("reason", "");
        ctx.put("triageNote", "");
        ctx.put("engine", "");
        ctx.put("recipe", "");
        ctx.put("stepsLeft", 4);
        ctx.put("observations", "OVERVIEW: 1 messages, indices 0..0.");

        String out = renderer.render(promptPrefix, ctx);

        assertThat(out)
                .contains("Feature: dark mode")
                .contains("Steps remaining: 4")
                // Conditional lines fed blank strings must be suppressed.
                .doesNotContain("Why it was opened:")
                .doesNotContain("Triage note:");
    }
}
