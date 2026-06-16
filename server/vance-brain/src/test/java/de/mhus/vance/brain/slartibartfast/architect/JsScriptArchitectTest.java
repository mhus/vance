package de.mhus.vance.brain.slartibartfast.architect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.slartibartfast.OutputSchemaType;
import de.mhus.vance.api.slartibartfast.RecipeDraft;
import de.mhus.vance.api.slartibartfast.ValidationCheck;
import de.mhus.vance.brain.hactar.HactarService;
import de.mhus.vance.brain.hactar.HactarService.Severity;
import de.mhus.vance.brain.hactar.HactarService.ValidationIssue;
import de.mhus.vance.brain.hactar.HactarService.ValidationRequest;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link JsScriptArchitect}. Verifies that the
 * architect declares the right schema metadata (non-recipe output,
 * scripts/.js path), and that {@link JsScriptArchitect#validateDraftShape}
 * delegates correctly to {@link HactarService} and maps results
 * into {@link ValidationCheck} entries.
 */
class JsScriptArchitectTest {

    private HactarService hactarService;
    private JsScriptArchitect architect;
    private ThinkProcessDocument process;

    @BeforeEach
    void setUp() {
        hactarService = mock(HactarService.class);
        architect = new JsScriptArchitect(hactarService);
        process = new ThinkProcessDocument();
        process.setId("proc-1");
        process.setTenantId("acme");
        process.setProjectId("test-project");
    }

    // ──────────────────── schema metadata ────────────────────

    @Test
    void declaresScriptJsAsSchemaType() {
        assertThat(architect.type()).isEqualTo(OutputSchemaType.SCRIPT_JS);
    }

    @Test
    void declaresNonRecipeOutputWithScriptsPath() {
        assertThat(architect.isRecipeOutput()).isFalse();
        assertThat(architect.outputPathSegment()).isEqualTo("scripts");
        assertThat(architect.outputExtension()).isEqualTo(".js");
    }

    @Test
    void disablesPathPersistenceAndSubRecipeListing() {
        assertThat(architect.wantsPathPersistenceCheck()).isFalse();
        assertThat(architect.wantsSubRecipeListing()).isFalse();
    }

    @Test
    void expectedEngineNameIsEmptyForNonRecipeOutput() {
        // ValidatingPhase skips the engine-field check when
        // isRecipeOutput=false; the value is never consulted but
        // should be a defensive non-null marker.
        assertThat(architect.expectedEngineName()).isEmpty();
    }

    @Test
    void systemPromptAndHintTailAreNonEmpty() {
        assertThat(architect.proposingSystemPrompt())
                .isNotBlank()
                .contains("JavaScript")
                .contains("name")
                .contains("code");
        assertThat(architect.recoveryHintTail(process)).isNotBlank();
    }

    // ──────────────────── validateDraftShape ────────────────────

    @Test
    void validate_delegatesToHactarService_passingCorrectScope() {
        RecipeDraft draft = scriptDraft("mailbot", "var x = 1;");
        when(hactarService.validate(any(ValidationRequest.class)))
                .thenReturn(new HactarService.ValidationResult(
                        true, List.of(), Duration.ZERO));
        List<ValidationCheck> report = new ArrayList<>();

        ValidationCheck firstFail = architect.validateDraftShape(
                draft, /*recipeMap*/ null, process, report);

        ArgumentCaptor<ValidationRequest> req = ArgumentCaptor.forClass(ValidationRequest.class);
        verify(hactarService).validate(req.capture());
        assertThat(req.getValue().code()).isEqualTo("var x = 1;");
        assertThat(req.getValue().language()).isEqualTo("js");
        assertThat(req.getValue().sourceName()).isEqualTo("mailbot.js");
        assertThat(req.getValue().tenantId()).isEqualTo("acme");
        assertThat(req.getValue().projectId()).isEqualTo("test-project");
        assertThat(req.getValue().processId()).isEqualTo("proc-1");
        assertThat(firstFail).isNull();
        assertThat(report)
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.isPassed()).isTrue();
                    assertThat(c.getRule()).isEqualTo(
                            JsScriptArchitect.RULE_SCRIPT_JS_VALID);
                });
    }

    @Test
    void validate_aggregatesHactarIssuesIntoSingleFailingCheck() {
        RecipeDraft draft = scriptDraft("broken", "function f(");
        when(hactarService.validate(any(ValidationRequest.class)))
                .thenReturn(HactarService.ValidationResult.fail(
                        List.of(
                                new ValidationIssue(
                                        Severity.ERROR, "syntax",
                                        "Unexpected end of input",
                                        3, 12),
                                new ValidationIssue(
                                        Severity.ERROR, "missing_required_tool",
                                        "@requiresTools declares 'mail_send' but caller lacks it",
                                        null, null)),
                        Duration.ZERO));
        List<ValidationCheck> report = new ArrayList<>();

        ValidationCheck firstFail = architect.validateDraftShape(
                draft, null, process, report);

        assertThat(firstFail).isNotNull();
        assertThat(firstFail.isPassed()).isFalse();
        assertThat(firstFail.getRule())
                .isEqualTo(JsScriptArchitect.RULE_SCRIPT_JS_VALID);
        assertThat(firstFail.getMessage())
                .contains("syntax")
                .contains("missing_required_tool")
                .contains("Unexpected end of input")
                .contains("line 3:12");
        assertThat(report).containsExactly(firstFail);
    }

    @Test
    void validate_wrapsHactarServiceException() {
        RecipeDraft draft = scriptDraft("crash", "var x = 1;");
        when(hactarService.validate(any(ValidationRequest.class)))
                .thenThrow(new RuntimeException("LightLlm provider down"));
        List<ValidationCheck> report = new ArrayList<>();

        ValidationCheck firstFail = architect.validateDraftShape(
                draft, null, process, report);

        assertThat(firstFail).isNotNull();
        assertThat(firstFail.isPassed()).isFalse();
        assertThat(firstFail.getMessage())
                .contains("HactarService.validate threw")
                .contains("LightLlm provider down");
    }

    @Test
    void validate_usesPlaceholderSourceNameWhenDraftNameBlank() {
        RecipeDraft draft = RecipeDraft.builder()
                .name("")
                .yaml("var x = 1;")
                .outputSchemaType(OutputSchemaType.SCRIPT_JS)
                .build();
        when(hactarService.validate(any(ValidationRequest.class)))
                .thenReturn(new HactarService.ValidationResult(
                        true, List.of(), Duration.ZERO));

        architect.validateDraftShape(draft, null, process, new ArrayList<>());

        ArgumentCaptor<ValidationRequest> req = ArgumentCaptor.forClass(ValidationRequest.class);
        verify(hactarService).validate(req.capture());
        assertThat(req.getValue().sourceName()).isEqualTo("<slart-script>");
    }

    // ──────────────────── extractRecipeYaml + name ────────────────────

    @Test
    void extractRecipeYaml_readsCodeField() {
        Map<String, Object> json = Map.of(
                "name", "mailbot",
                "code", "var x = 42;");

        String yaml = architect.extractRecipeYaml(json);

        assertThat(yaml).isEqualTo("var x = 42;");
    }

    @Test
    void extractRecipeYaml_throwsOnMissingCode() {
        Map<String, Object> json = Map.of("name", "x");

        assertThatThrownBy(() -> architect.extractRecipeYaml(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("code");
    }

    @Test
    void extractRecipeYaml_throwsOnBlankCode() {
        Map<String, Object> json = Map.of("name", "x", "code", "");

        assertThatThrownBy(() -> architect.extractRecipeYaml(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("code");
    }

    @Test
    void extractRecipeName_usesDefaultImplementation() {
        // Inherited from SchemaArchitect; SCRIPT_JS doesn't override.
        Map<String, Object> json = Map.of("name", "mailbot", "code", "var x;");
        assertThat(architect.extractRecipeName(json)).isEqualTo("mailbot");
    }

    // ──────────────────── appendProposingContext (UPDATE mode) ────────────────────

    @Test
    void appendProposingContext_noopInCreateMode() {
        de.mhus.vance.api.slartibartfast.ArchitectState state =
                de.mhus.vance.api.slartibartfast.ArchitectState.builder()
                        .mode(de.mhus.vance.api.slartibartfast.ArchitectMode.CREATE)
                        .existingScriptCode("var x = 1;")
                        .priorFailureReason("Previous run timed out.")
                        .build();
        StringBuilder sb = new StringBuilder("preamble\n");

        architect.appendProposingContext(sb, state, List.of());

        // CREATE mode: existing-script payload is suppressed even
        // if the state somehow carries it (defensive).
        assertThat(sb.toString()).isEqualTo("preamble\n");
    }

    @Test
    void appendProposingContext_injectsExistingScriptInUpdateMode() {
        de.mhus.vance.api.slartibartfast.ArchitectState state =
                de.mhus.vance.api.slartibartfast.ArchitectState.builder()
                        .mode(de.mhus.vance.api.slartibartfast.ArchitectMode.UPDATE)
                        .existingScriptRef("scripts/mailbot.js")
                        .existingScriptCode("var x = 1;\nfunction main() { /* ... */ }")
                        .build();
        StringBuilder sb = new StringBuilder();

        architect.appendProposingContext(sb, state, List.of());

        String out = sb.toString();
        assertThat(out)
                .contains("## EXISTING SCRIPT")
                .contains("scripts/mailbot.js")
                .contains("```javascript")
                .contains("function main()");
        assertThat(out).doesNotContain("## FAILURE REASON");
    }

    @Test
    void appendProposingContext_injectsFailureReasonInUpdateMode() {
        de.mhus.vance.api.slartibartfast.ArchitectState state =
                de.mhus.vance.api.slartibartfast.ArchitectState.builder()
                        .mode(de.mhus.vance.api.slartibartfast.ArchitectMode.UPDATE)
                        .existingScriptRef("scripts/mailbot.js")
                        .existingScriptCode("var x = 1;")
                        .priorFailureReason("Hactar timed out after 30m\nstuck on imap_fetch")
                        .build();
        StringBuilder sb = new StringBuilder();

        architect.appendProposingContext(sb, state, List.of());

        String out = sb.toString();
        assertThat(out)
                .contains("## EXISTING SCRIPT")
                .contains("## FAILURE REASON")
                .contains("Hactar timed out after 30m")
                .contains("> stuck on imap_fetch");
    }

    @Test
    void appendProposingContext_skipsBlankExistingCode() {
        de.mhus.vance.api.slartibartfast.ArchitectState state =
                de.mhus.vance.api.slartibartfast.ArchitectState.builder()
                        .mode(de.mhus.vance.api.slartibartfast.ArchitectMode.UPDATE)
                        .existingScriptCode("")
                        .priorFailureReason("Something broke.")
                        .build();
        StringBuilder sb = new StringBuilder();

        architect.appendProposingContext(sb, state, List.of());

        // Failure reason still surfaces even without re-feeding the
        // body — useful when the caller wants the LLM to learn from
        // the failure context alone (rare but defensible).
        assertThat(sb.toString())
                .doesNotContain("## EXISTING SCRIPT")
                .contains("## FAILURE REASON")
                .contains("Something broke.");
    }

    // ──────────────────── helpers ────────────────────

    private static RecipeDraft scriptDraft(String name, String code) {
        return RecipeDraft.builder()
                .name(name)
                .yaml(code)
                .outputSchemaType(OutputSchemaType.SCRIPT_JS)
                .confidence(0.8)
                .build();
    }
}
