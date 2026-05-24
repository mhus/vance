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
 * Pins the template-driven YAML rendering in {@link MarvinArchitect}.
 * Constructs a realistic {@code research-aggregate-write} params
 * payload and checks the rendered YAML carries the expected
 * structural anchors. Catches template syntax regressions and
 * verifies the inner {@code {% verbatim %}} blocks preserve
 * Marvin-runtime Pebble references.
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
    void researchAggregateWrite_rendersValidYamlWithExpectedAnchors() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", "deep-research");
        params.put("description",
                "Systematic web research with synthesized report.");
        params.put("gathererRecipe", "web-research");
        params.put("aspects", List.of(
                Map.of("role", "history", "goal", "History and political context."),
                Map.of("role", "tech",    "goal", "Technology and current state of the art.")));
        params.put("synthesisPrompt", "Verdichte zu einem Bericht.");
        params.put("language", "de");
        params.put("reportLengthWords", "1500-2000");
        params.put("outputPathTpl",
                "research/{{ process.goal | slug }}/report.md");

        Map<String, Object> jsonRoot = new LinkedHashMap<>();
        jsonRoot.put("templateId", "research-aggregate-write");
        jsonRoot.put("params", params);

        String yaml = architect.extractRecipeYaml(jsonRoot);

        // Structural anchors — guarantees the rendered YAML is
        // shaped like a Marvin recipe with the template's contract.
        assertThat(yaml)
                .contains("engine: marvin")
                .contains("name: deep-research")
                .contains("rootTaskKind: PLAN")
                .contains("- web-research");

        // promptPrefix carries the literal Marvin-runtime Pebble
        // (preserved through {% verbatim %}). The outer render
        // MUST NOT consume these.
        assertThat(yaml)
                .contains("{{ process.goal }}")
                .contains("{{ node.summary }}")
                // and the outputPathTpl value, which itself contains
                // Pebble, is emitted verbatim
                .contains("research/{{ process.goal | slug }}/report.md");

        // 2 aspects + 1 AGGREGATE = 3 children declared
        assertThat(yaml).contains("EXACTLY 3 children");
        assertThat(yaml).contains("KIND 1 — WORKER web-research");
        assertThat(yaml).contains("KIND 2 — WORKER web-research");
        assertThat(yaml).contains("KIND 3 — AGGREGATE");

        // postAction is the engine-side write — uses canonical
        // tool/args keys.
        assertThat(yaml)
                .contains("\"tool\":\"doc_write_text\"")
                .contains("\"path\":\"research/{{ process.goal | slug }}/report.md\"");

        // No misplaced postActions at YAML level — only inside the
        // promptPrefix's embedded KIND-block JSON.
        long yamlLevelPostActions = yaml.lines()
                .filter(l -> l.matches("\\s*postActions\\s*:.*"))
                .count();
        assertThat(yamlLevelPostActions).isZero();
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
        params.put("gathererRecipe", "web-research");
        params.put("aspects", List.of(Map.of("role", "r", "goal", "g")));
        params.put("synthesisPrompt", "s");
        params.put("language", "de");
        params.put("outputPathTpl", "_user/x.md"); // reserved bucket
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
        params.put("outlinePrompt", "Erstelle eine Gliederung in 4 Kapiteln.");
        params.put("outlinePath",
                "essays/{{ process.goal | slug }}/outline.md");
        params.put("chaptersDir",
                "essays/{{ process.goal | slug }}/chapters");
        params.put("chapterPromptTpl",
                "Schreibe Kapitel: {{ item.text }}");
        params.put("language", "de");
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
                .contains("EXACTLY 3 children")          // with consolidate
                .contains("KIND 1 — WORKER marvin-worker")
                .contains("KIND 2 — EXPAND_FROM_DOC")
                .contains("KIND 3 — AGGREGATE")
                .contains("{{ process.goal | slug }}")    // path-template preserved
                .contains("{{ item.text }}")              // EXPAND iteration var preserved
                .contains("{{ node.result }}")            // postAction content preserved
                .contains("{{ node.summary }}");          // AGGREGATE postAction content
    }

    @Test
    void docDrivenChapters_withoutConsolidation_omitsAggregate() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", "essay-noconsolidate");
        params.put("description", "Outline + chapters only.");
        params.put("outlinePrompt", "Erstelle eine Gliederung.");
        params.put("outlinePath", "essays/o.md");
        params.put("chaptersDir", "essays/chapters");
        params.put("chapterPromptTpl", "Schreibe: {{ item.text }}");
        params.put("language", "de");
        params.put("consolidate", false);
        Map<String, Object> jsonRoot = Map.of(
                "templateId", "doc-driven-chapters",
                "params", params);
        String yaml = architect.extractRecipeYaml(jsonRoot);
        assertThat(yaml)
                .contains("EXACTLY 2 children")
                .doesNotContain("AGGREGATE");
    }

    @Test
    void docDrivenChapters_consolidateOnButFinalPathMissing_throws() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", "x");
        params.put("description", "d");
        params.put("outlinePrompt", "o");
        params.put("outlinePath", "essays/o.md");
        params.put("chaptersDir", "essays/chapters");
        params.put("chapterPromptTpl", "Schreibe: {{ item.text }}");
        params.put("language", "de");
        params.put("consolidate", true);
        // missing consolidatePrompt + finalPath
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
        params.put("language", "de");

        Map<String, Object> jsonRoot = Map.of(
                "templateId", "decide-with-user-input",
                "params", params);
        String yaml = architect.extractRecipeYaml(jsonRoot);

        assertThat(yaml)
                .contains("name: wochenend-projekt")
                .contains("EXACTLY 3 children")          // 2 USER_INPUT + 1 WORKER
                .contains("KIND 1 — USER_INPUT")
                .contains("KIND 2 — USER_INPUT")
                .contains("KIND 3 — WORKER marvin-worker")
                .contains("\"type\":\"DECISION\"")
                .contains("\"type\":\"FEEDBACK\"")
                .contains("{{ process.goal | slug }}")
                .contains("{{ node.result }}");
    }

    @Test
    void decideWithUserInput_emptyQuestions_throws() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", "x");
        params.put("description", "d");
        params.put("questions", List.of());
        params.put("decisionPrompt", "p");
        params.put("outputPathTpl", "decisions/x.md");
        params.put("language", "de");
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
        params.put("questions", List.of(
                Map.of("title", "T", "body", "B", "type", "BOGUS_TYPE")));
        params.put("decisionPrompt", "p");
        params.put("outputPathTpl", "decisions/x.md");
        params.put("language", "de");
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
        params.put("gathererRecipe", "web-research");
        params.put("aspects", List.of());
        params.put("synthesisPrompt", "s");
        params.put("language", "de");
        params.put("outputPathTpl", "research/x.md");
        Map<String, Object> jsonRoot = Map.of(
                "templateId", "research-aggregate-write",
                "params", params);
        assertThatThrownBy(() -> architect.extractRecipeYaml(jsonRoot))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty list");
    }
}
