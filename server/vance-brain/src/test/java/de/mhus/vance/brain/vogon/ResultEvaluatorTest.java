package de.mhus.vance.brain.vogon;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.vogon.ResultSpec;
import de.mhus.vance.api.vogon.StrategyState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResultEvaluatorTest {

    @Test
    void evaluate_returnsNullWhenSpecIsNull() {
        ResultEvaluator.Outcome outcome = ResultEvaluator.evaluate(
                /*spec*/ null, Map.of(), new StrategyState());
        assertThat(outcome).isNull();
    }

    @Test
    void evaluate_typePreservingSingleRef() {
        StrategyState state = new StrategyState();
        state.getFlags().put("wordCount", 437);
        state.getFlags().put("ready", Boolean.TRUE);
        state.getFlags().put("urls", List.of("https://a", "https://b"));

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("count", "${flags.wordCount}");
        fields.put("isReady", "${flags.ready}");
        fields.put("sources", "${flags.urls}");
        ResultSpec spec = ResultSpec.builder().fields(fields).build();

        ResultEvaluator.Outcome outcome = ResultEvaluator.evaluate(
                spec, Map.of(), state);
        assertThat(outcome).isNotNull();
        assertThat(outcome.payload())
                .containsEntry("count", 437)
                .containsEntry("isReady", Boolean.TRUE);
        assertThat(outcome.payload().get("sources"))
                .asList()
                .containsExactly("https://a", "https://b");
    }

    @Test
    void evaluate_interpolatedFieldStaysString() {
        Map<String, Object> params = Map.of("topic", "Latex+Java");
        StrategyState state = new StrategyState();
        state.getFlags().put("wordCount", 437);

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("headline", "${params.topic} (${flags.wordCount} words)");
        ResultSpec spec = ResultSpec.builder().fields(fields).build();

        ResultEvaluator.Outcome outcome = ResultEvaluator.evaluate(
                spec, params, state);
        assertThat(outcome.payload())
                .containsEntry("headline", "Latex+Java (437 words)");
    }

    @Test
    void evaluate_textReferencesResultFields() {
        Map<String, Object> params = Map.of("topic", "Latex+Java");
        StrategyState state = new StrategyState();
        state.getFlags().put("draftPath", "reports/latex-java.md");
        state.getFlags().put("wordCount", 437);

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("documentPath", "${flags.draftPath}");
        fields.put("wordCount", "${flags.wordCount}");
        ResultSpec spec = ResultSpec.builder()
                .fields(fields)
                .text("Report zu ${params.topic} liegt unter "
                        + "`${result.documentPath}` (${result.wordCount} Wörter).")
                .build();

        ResultEvaluator.Outcome outcome = ResultEvaluator.evaluate(
                spec, params, state);
        assertThat(outcome.text())
                .isEqualTo("Report zu Latex+Java liegt unter "
                        + "`reports/latex-java.md` (437 Wörter).");
        // Payload survives unchanged — typed.
        assertThat(outcome.payload())
                .containsEntry("documentPath", "reports/latex-java.md")
                .containsEntry("wordCount", 437);
    }

    @Test
    void evaluate_unresolvedRefBecomesNullField() {
        StrategyState state = new StrategyState();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("missing", "${flags.notSet}");
        ResultSpec spec = ResultSpec.builder().fields(fields).build();

        ResultEvaluator.Outcome outcome = ResultEvaluator.evaluate(
                spec, Map.of(), state);
        assertThat(outcome.payload()).containsEntry("missing", null);
    }

    @Test
    void evaluate_blankTextProducesNullTextWithPayloadStillPopulated() {
        StrategyState state = new StrategyState();
        state.getFlags().put("k", "v");
        ResultSpec spec = ResultSpec.builder()
                .fields(Map.of("k", "${flags.k}"))
                .text("   ")
                .build();
        ResultEvaluator.Outcome outcome = ResultEvaluator.evaluate(
                spec, Map.of(), state);
        assertThat(outcome.text()).isNull();
        assertThat(outcome.payload()).containsEntry("k", "v");
    }

    @Test
    void evaluate_readsPhaseArtifactsViaSpecForm() {
        // Spec §3.2 example uses ${phases.X.artifacts.Y} — the
        // resolver strips the .artifacts. infix.
        StrategyState state = new StrategyState();
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("result", "Draft text");
        draft.put("sources", List.of("https://a"));
        state.getPhaseArtifacts().put("research", draft);

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("text", "${phases.research.artifacts.result}");
        fields.put("sources", "${phases.research.artifacts.sources}");
        // Legacy form also still works.
        fields.put("legacy", "${phases.research.result}");
        ResultSpec spec = ResultSpec.builder().fields(fields).build();

        ResultEvaluator.Outcome outcome = ResultEvaluator.evaluate(
                spec, Map.of(), state);
        assertThat(outcome.payload())
                .containsEntry("text", "Draft text")
                .containsEntry("legacy", "Draft text");
        assertThat(outcome.payload().get("sources"))
                .asList()
                .containsExactly("https://a");
    }
}
