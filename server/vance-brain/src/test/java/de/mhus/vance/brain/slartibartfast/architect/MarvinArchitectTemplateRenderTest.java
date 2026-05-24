package de.mhus.vance.brain.slartibartfast.architect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.brain.recipe.RecipeLoader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the template-driven YAML rendering in {@link MarvinArchitect}
 * for the Marvin v2 model. The architect emits short recipes:
 * engine=marvin, params.availableRecipes, narrative promptPrefix —
 * no KIND blocks, no PLAN/AGGREGATE TaskKinds.
 */
class MarvinArchitectTemplateRenderTest {

    private MarvinArchitect architect;

    @BeforeEach
    void setUp() {
        architect = new MarvinArchitect(
                mock(RecipeLoader.class),
                new PromptTemplateRenderer());
    }

    @Test
    void researchAggregateWrite_rendersMarvinV2Recipe() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", "deep-research");
        params.put("description",
                "Systematic web research with synthesized report.");
        params.put("language", "de");
        params.put("availableRecipes", List.of("web-research"));
        params.put("aspects", List.of(
                Map.of("role", "history", "goal", "History and political context."),
                Map.of("role", "tech",    "goal", "Technology and current state of the art.")));
        params.put("synthesisPrompt", "Verdichte zu einem Bericht.");
        params.put("reportLengthWords", "1500-2000");
        params.put("outputPathTpl",
                "research/{{ process.goal | slug }}/report.md");

        Map<String, Object> jsonRoot = new LinkedHashMap<>();
        jsonRoot.put("templateId", "research-aggregate-write");
        jsonRoot.put("params", params);

        String yaml = architect.extractRecipeYaml(jsonRoot);

        assertThat(yaml)
                .contains("engine: marvin")
                .contains("name: deep-research")
                .contains("availableRecipes:")
                .contains("- web-research")
                .doesNotContain("rootTaskKind")
                .doesNotContain("AGGREGATE")
                .doesNotContain("allowedSubTaskRecipes")
                .doesNotContain("KIND ");

        // Marvin-runtime Pebble preserved through {% verbatim %}.
        assertThat(yaml)
                .contains("{{ process.goal }}")
                .contains("research/{{ process.goal | slug }}/report.md")
                .contains("{{ node.result }}");

        // Aspects mentioned in the narrative.
        assertThat(yaml)
                .contains("history: History and political context.")
                .contains("tech: Technology and current state of the art.");

        // postAction is engine-side write.
        assertThat(yaml).contains("doc_write_text");
    }

    @Test
    void missingTemplateId_throws() {
        Map<String, Object> jsonRoot = Map.of(
                "params", Map.of("name", "x"));
        assertThatThrownBy(() -> architect.extractRecipeYaml(jsonRoot))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("templateId");
    }

    @Test
    void unsupportedTemplateId_throws() {
        Map<String, Object> jsonRoot = Map.of(
                "templateId", "bogus-template",
                "params", Map.of("name", "x"));
        assertThatThrownBy(() -> architect.extractRecipeYaml(jsonRoot))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not supported");
    }

    @Test
    void reservedOutputPath_throws() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", "x");
        params.put("description", "d");
        params.put("language", "de");
        params.put("availableRecipes", List.of("web-research"));
        params.put("aspects", List.of(Map.of("role", "r", "goal", "g")));
        params.put("synthesisPrompt", "s");
        params.put("outputPathTpl", "_user/x.md");
        Map<String, Object> jsonRoot = Map.of(
                "templateId", "research-aggregate-write",
                "params", params);
        assertThatThrownBy(() -> architect.extractRecipeYaml(jsonRoot))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved prefix");
    }

    @Test
    void docDrivenChapters_rendersWithConsolidation() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", "essay-pipeline");
        params.put("description", "Outline + chapters + final essay.");
        params.put("language", "de");
        params.put("availableRecipes", List.of());
        params.put("outlinePrompt", "Erstelle eine Gliederung in 4 Kapiteln.");
        params.put("outlinePath",
                "essays/{{ process.goal | slug }}/outline.md");
        params.put("chaptersDir",
                "essays/{{ process.goal | slug }}/chapters");
        params.put("chapterPromptTpl",
                "Schreibe Kapitel: {{ item.text }}");
        params.put("consolidate", true);
        params.put("consolidatePrompt",
                "Konsolidiere die Kapitel zu einem Essay.");
        params.put("finalPath",
                "essays/{{ process.goal | slug }}/final.md");

        Map<String, Object> jsonRoot = Map.of(
                "templateId", "doc-driven-chapters",
                "params", params);
        String yaml = architect.extractRecipeYaml(jsonRoot);

        assertThat(yaml)
                .contains("name: essay-pipeline")
                .contains("engine: marvin")
                .contains("EXPAND_FROM_DOC")
                .contains("Schritt 1")
                .contains("Schritt 2")
                .contains("Schritt 3")
                .contains("{{ process.goal }}")
                .contains("{{ node.result }}");
    }

    @Test
    void docDrivenChapters_withoutConsolidation_omitsStep3() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", "essay-noconsolidate");
        params.put("description", "Outline + chapters only.");
        params.put("language", "de");
        params.put("availableRecipes", List.of());
        params.put("outlinePrompt", "Erstelle eine Gliederung.");
        params.put("outlinePath", "essays/o.md");
        params.put("chaptersDir", "essays/chapters");
        params.put("chapterPromptTpl", "Schreibe: {{ item.text }}");
        params.put("consolidate", false);
        Map<String, Object> jsonRoot = Map.of(
                "templateId", "doc-driven-chapters",
                "params", params);
        String yaml = architect.extractRecipeYaml(jsonRoot);
        assertThat(yaml)
                .contains("Schritt 1")
                .contains("Schritt 2")
                .doesNotContain("Schritt 3");
    }

    @Test
    void docDrivenChapters_consolidateOnButFinalPathMissing_throws() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", "x");
        params.put("description", "d");
        params.put("language", "de");
        params.put("availableRecipes", List.of());
        params.put("outlinePrompt", "o");
        params.put("outlinePath", "essays/o.md");
        params.put("chaptersDir", "essays/chapters");
        params.put("chapterPromptTpl", "Schreibe: {{ item.text }}");
        params.put("consolidate", true);
        Map<String, Object> jsonRoot = Map.of(
                "templateId", "doc-driven-chapters",
                "params", params);
        assertThatThrownBy(() -> architect.extractRecipeYaml(jsonRoot))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("consolidatePrompt");
    }

    @Test
    void decideWithUserInput_rendersExpectedAnchors() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", "wochenend-projekt");
        params.put("description", "Plan a weekend project after clarifications.");
        params.put("language", "de");
        params.put("availableRecipes", List.of());
        params.put("questions", List.of(
                Map.of("role", "skill", "title", "Skill?",
                        "body", "Anfänger or fortgeschritten?",
                        "type", "DECISION",
                        "options", List.of("Anfänger", "Mittel", "Fortgeschritten")),
                Map.of("role", "budget", "title", "Budget?",
                        "body", "Wie viel ausgeben?",
                        "type", "FEEDBACK")));
        params.put("decisionPrompt",
                "Basierend auf den Antworten, plane in 5 Schritten.");
        params.put("outputPathTpl",
                "decisions/{{ process.goal | slug }}/plan.md");

        Map<String, Object> jsonRoot = Map.of(
                "templateId", "decide-with-user-input",
                "params", params);
        String yaml = architect.extractRecipeYaml(jsonRoot);

        assertThat(yaml)
                .contains("name: wochenend-projekt")
                .contains("engine: marvin")
                .contains("Skill?")
                .contains("Budget?")
                .contains("DECISION")
                .contains("FEEDBACK")
                .contains("{{ process.goal | slug }}")
                .contains("{{ node.result }}");
    }

    @Test
    void decideWithUserInput_emptyQuestions_throws() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", "x");
        params.put("description", "d");
        params.put("language", "de");
        params.put("availableRecipes", List.of());
        params.put("questions", List.of());
        params.put("decisionPrompt", "p");
        params.put("outputPathTpl", "decisions/x.md");
        Map<String, Object> jsonRoot = Map.of(
                "templateId", "decide-with-user-input",
                "params", params);
        assertThatThrownBy(() -> architect.extractRecipeYaml(jsonRoot))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty list");
    }

    @Test
    void decideWithUserInput_invalidQuestionType_throws() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", "x");
        params.put("description", "d");
        params.put("language", "de");
        params.put("availableRecipes", List.of());
        params.put("questions", List.of(
                Map.of("title", "T", "body", "B", "type", "BOGUS_TYPE")));
        params.put("decisionPrompt", "p");
        params.put("outputPathTpl", "decisions/x.md");
        Map<String, Object> jsonRoot = Map.of(
                "templateId", "decide-with-user-input",
                "params", params);
        assertThatThrownBy(() -> architect.extractRecipeYaml(jsonRoot))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BOGUS_TYPE");
    }

    @Test
    void emptyAspects_throws() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", "x");
        params.put("description", "d");
        params.put("language", "de");
        params.put("availableRecipes", List.of("web-research"));
        params.put("aspects", List.of());
        params.put("synthesisPrompt", "s");
        params.put("outputPathTpl", "research/x.md");
        Map<String, Object> jsonRoot = Map.of(
                "templateId", "research-aggregate-write",
                "params", params);
        assertThatThrownBy(() -> architect.extractRecipeYaml(jsonRoot))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty list");
    }

    @Test
    void missingLanguage_throws() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", "x");
        params.put("description", "d");
        params.put("availableRecipes", List.of());
        params.put("aspects", List.of(Map.of("role", "r", "goal", "g")));
        params.put("synthesisPrompt", "s");
        params.put("outputPathTpl", "research/x.md");
        Map<String, Object> jsonRoot = Map.of(
                "templateId", "research-aggregate-write",
                "params", params);
        assertThatThrownBy(() -> architect.extractRecipeYaml(jsonRoot))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("language");
    }
}
