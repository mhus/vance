package de.mhus.vance.brain.vogon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.shared.magrathea.MagratheaWorkflowLoader;
import de.mhus.vance.shared.magrathea.ResolvedMagratheaWorkflow;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The intake stage: a spoken request becomes the parameters a plan asked
 * for — and the model is the last resort, not the first.
 */
class VogonIntakeTest {

    private static final String PLAN_YAML = """
            start: work
            parameters:
              version: { type: string, required: true }
              channel: { type: string, required: false, default: stable }
            states:
              work:
                type: terminal
                outcome: success
            """;

    @SuppressWarnings("unchecked")
    private final ObjectProvider<LightLlmService> lightLlmProvider = mock(ObjectProvider.class);
    private final LightLlmService lightLlm = mock(LightLlmService.class);
    private final MagratheaWorkflowLoader workflowLoader = mock(MagratheaWorkflowLoader.class);
    private final de.mhus.vance.shared.document.DocumentService documentService =
            mock(de.mhus.vance.shared.document.DocumentService.class);

    private final VogonIntake intake =
            new VogonIntake(lightLlmProvider, workflowLoader, documentService);

    private static ResolvedMagratheaWorkflow plan() {
        return MagratheaWorkflowLoader.parseYaml("release", PLAN_YAML);
    }

    private void llmAvailable(Map<String, Object> answer) {
        when(lightLlmProvider.getIfAvailable()).thenReturn(lightLlm);
        when(lightLlm.callForJson(any())).thenReturn(answer);
    }

    // ──────────── the model is the last stage ────────────

    @Test
    void allRequiredParamsGiven_noModelCall() {
        when(lightLlmProvider.getIfAvailable()).thenReturn(lightLlm);

        VogonIntake.Outcome out = intake.resolve("t", "p", plan(), "release",
                Map.of("version", "1.0.0"), "release version 1.0.0", null);

        assertThat(out.params()).containsEntry("version", "1.0.0");
        assertThat(out.derivedKeys()).isEmpty();
        verify(lightLlm, never()).callForJson(any());
    }

    @Test
    void missingRequiredParam_isReadOutOfTheText() {
        llmAvailable(Map.of("version", "1.0.0"));

        VogonIntake.Outcome out = intake.resolve("t", "p", plan(), "release",
                Map.of(), "release version 1.0.0", null);

        assertThat(out.params()).containsEntry("version", "1.0.0");
        assertThat(out.derivedKeys()).containsExactly("version");
        assertThat(out.planName()).isEqualTo("release");
    }

    @Test
    void explicitParamBeatsWhatTheTextSays() {
        // The caller was specific; a reading of prose must not override it.
        llmAvailable(Map.of("version", "9.9.9"));

        VogonIntake.Outcome out = intake.resolve("t", "p", plan(), "release",
                Map.of("version", "1.0.0"), "release version 9.9.9", null);

        assertThat(out.params()).containsEntry("version", "1.0.0");
    }

    @Test
    void intakeNone_neverCallsTheModel() {
        when(lightLlmProvider.getIfAvailable()).thenReturn(lightLlm);

        VogonIntake.Outcome out = intake.resolve("t", "p", plan(), "release",
                Map.of(), "release version 1.0.0", VogonIntake.INTAKE_NONE);

        assertThat(out.params()).isEmpty();
        verify(lightLlm, never()).callForJson(any());
    }

    @Test
    void noTaskText_leavesTheGapForTheStartPathToReport() {
        when(lightLlmProvider.getIfAvailable()).thenReturn(lightLlm);

        VogonIntake.Outcome out = intake.resolve("t", "p", plan(), "release",
                Map.of(), null, null);

        assertThat(out.params()).isEmpty();
        verify(lightLlm, never()).callForJson(any());
    }

    @Test
    void aFailedReadingIsNotAFailedRun() {
        when(lightLlmProvider.getIfAvailable()).thenReturn(lightLlm);
        when(lightLlm.callForJson(any())).thenThrow(new IllegalStateException("model down"));

        VogonIntake.Outcome out = intake.resolve("t", "p", plan(), "release",
                Map.of(), "release version 1.0.0", null);

        // Nothing derived — the start path reports the missing field by name,
        // which is a better error than "the model was unavailable".
        assertThat(out.derivedKeys()).isEmpty();
        assertThat(out.planName()).isEqualTo("release");
    }

    // ──────────── picking the plan ────────────

    @Test
    void whenNoPlanIsNamed_theChoiceIsAnEnumOverPlansThatExist() {
        when(workflowLoader.listAll(any(), any())).thenReturn(List.of(
                MagratheaWorkflowLoader.parseYaml("release", PLAN_YAML),
                MagratheaWorkflowLoader.parseYaml("deploy", PLAN_YAML)));
        llmAvailable(Map.of("plan", "release"));

        VogonIntake.Outcome out = intake.resolve("t", "p", null, null,
                Map.of(), "please do a release", null);

        assertThat(out.planName()).isEqualTo("release");
        assertThat(out.derivedKeys()).contains("plan");

        ArgumentCaptor<LightLlmRequest> captor = ArgumentCaptor.forClass(LightLlmRequest.class);
        verify(lightLlm).callForJson(captor.capture());
        Map<String, Object> schema = captor.getValue().getSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> planField = (Map<String, Object>) props.get("plan");
        assertThat(planField.get("enum"))
                .as("the model picks from what resolves, it does not name a plan freely")
                .isEqualTo(List.of("deploy", "release"));
    }

    @Test
    void noPlansAtAll_yieldsNoPlanRatherThanAGuess() {
        when(workflowLoader.listAll(any(), any())).thenReturn(List.of());
        when(lightLlmProvider.getIfAvailable()).thenReturn(lightLlm);

        VogonIntake.Outcome out = intake.resolve("t", "p", null, null,
                Map.of(), "please do a release", null);

        assertThat(out.planName()).isNull();
        verify(lightLlm, never()).callForJson(any());
    }

    // ──────────── a path in the request ────────────

    @Test
    void aPathInTheRequestIsTakenStraight_withoutAskingAModel() {
        // The path is already written down. A model asked to copy it can only
        // get it less right, and it would cost a call to do so.
        when(lightLlmProvider.getIfAvailable()).thenReturn(lightLlm);

        VogonIntake.Outcome out = intake.resolve("t", "p", null, null,
                Map.of(), "starte mal vogon mit dem workflow workflows/helloworld.yaml", null);

        assertThat(out.planPath()).isEqualTo("workflows/helloworld.yaml");
        assertThat(out.planName()).isNull();
        verify(lightLlm, never()).callForJson(any());
    }

    @Test
    void sentencePunctuationAroundAPathIsTrimmed() {
        when(lightLlmProvider.getIfAvailable()).thenReturn(lightLlm);

        VogonIntake.Outcome out = intake.resolve("t", "p", null, null,
                Map.of(), "run _vance/workflows/hello.yaml.", null);

        assertThat(out.planPath()).isEqualTo("_vance/workflows/hello.yaml");
    }

    @Test
    void aBareNameStillGoesThroughTheEnum() {
        when(workflowLoader.listAll(any(), any())).thenReturn(List.of(
                MagratheaWorkflowLoader.parseYaml("helloworld", PLAN_YAML)));
        llmAvailable(Map.of("plan", "helloworld"));

        VogonIntake.Outcome out = intake.resolve("t", "p", null, null,
                Map.of(), "starte mal den workflow helloworld", null);

        assertThat(out.planName()).isEqualTo("helloworld");
        assertThat(out.planPath()).isNull();
        verify(lightLlm).callForJson(any());
    }

    @Test
    void aModelThatAnswersWithAPathIsUnderstoodAnyway() {
        // Right about the plan, wrong about the field — not worth failing on.
        when(workflowLoader.listAll(any(), any())).thenReturn(List.of(
                MagratheaWorkflowLoader.parseYaml("helloworld", PLAN_YAML)));
        llmAvailable(Map.of("plan", "docs/helloworld.yaml"));

        VogonIntake.Outcome out = intake.resolve("t", "p", null, null,
                Map.of(), "starte den hello-plan", null);

        assertThat(out.planPath()).isEqualTo("docs/helloworld.yaml");
        assertThat(out.planName()).isNull();
    }

    @Test
    void looksLikePath_separatesTheTwoAddressingForms() {
        assertThat(VogonIntake.looksLikePath("helloworld")).isFalse();
        assertThat(VogonIntake.looksLikePath("hello-world")).isFalse();
        assertThat(VogonIntake.looksLikePath("workflows/helloworld.yaml")).isTrue();
        assertThat(VogonIntake.looksLikePath("helloworld.yaml")).isTrue();
        assertThat(VogonIntake.looksLikePath("a/b")).isTrue();
        assertThat(VogonIntake.looksLikePath(null)).isFalse();
    }

    // ──────────── the gap calculation ────────────

    @Test
    void missingRequired_ignoresOptionalAndDefaulted() {
        assertThat(VogonIntake.missingRequired(plan(), Map.of()))
                .containsExactly("version");
        assertThat(VogonIntake.missingRequired(plan(), Map.of("version", "1.0.0")))
                .isEmpty();
    }
}
