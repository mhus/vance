package de.mhus.vance.brain.slartibartfast.phases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.slartibartfast.ArchitectMode;
import de.mhus.vance.api.slartibartfast.ArchitectState;
import de.mhus.vance.api.slartibartfast.ArchitectStatus;
import de.mhus.vance.api.slartibartfast.OutputSchemaType;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LoadingExistingPhase} — focuses on the
 * UPDATE branch (Phase 2b) added on top of the legacy EDIT-recipe
 * branch. EDIT happy-path is verified once for regression
 * coverage; the rest covers UPDATE failure modes + the mode-
 * branching gate at the top of {@code execute(...)}.
 */
class LoadingExistingPhaseTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "test-project";

    private DocumentService documentService;
    private LoadingExistingPhase phase;
    private ThinkProcessDocument process;
    private ThinkEngineContext ctx;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        phase = new LoadingExistingPhase(documentService);
        process = new ThinkProcessDocument();
        process.setId("proc-1");
        process.setTenantId(TENANT);
        process.setProjectId(PROJECT);
        ctx = mock(ThinkEngineContext.class);
    }

    // ──────────────────── UPDATE branch ────────────────────

    @Test
    void update_loadsScriptBodyFromExistingScriptRef() {
        ArchitectState state = ArchitectState.builder()
                .mode(ArchitectMode.UPDATE)
                .outputSchemaType(OutputSchemaType.SCRIPT_JS)
                .existingScriptRef("scripts/mailbot.js")
                .status(ArchitectStatus.LOADING_EXISTING)
                .build();
        DocumentDocument doc = new DocumentDocument();
        when(documentService.findByPath(
                eq(TENANT), eq(PROJECT), eq("scripts/mailbot.js")))
                .thenReturn(Optional.of(doc));
        when(documentService.readContent(doc))
                .thenReturn("/** @description test */\n(function(){})();");

        phase.execute(state, process, ctx);

        assertThat(state.getFailureReason()).isNull();
        assertThat(state.getExistingScriptCode())
                .contains("@description test")
                .contains("(function(){})()");
        // UPDATE doesn't touch the recipe-specific fields.
        assertThat(state.getExistingRecipeYaml()).isNull();
        assertThat(state.getExistingRecipeMap()).isNull();
        // Schema stays whatever the caller declared — UPDATE is
        // mode-orthogonal-to-schema, unlike EDIT which re-detects.
        assertThat(state.getOutputSchemaType()).isEqualTo(OutputSchemaType.SCRIPT_JS);
    }

    @Test
    void update_failsWhenExistingScriptRefIsBlank() {
        ArchitectState state = ArchitectState.builder()
                .mode(ArchitectMode.UPDATE)
                .outputSchemaType(OutputSchemaType.SCRIPT_JS)
                .existingScriptRef(null)
                .status(ArchitectStatus.LOADING_EXISTING)
                .build();

        phase.execute(state, process, ctx);

        assertThat(state.getFailureReason())
                .isNotNull()
                .contains("existingScriptRef");
        assertThat(state.getExistingScriptCode()).isNull();
    }

    @Test
    void update_failsWhenDocumentNotFound() {
        ArchitectState state = ArchitectState.builder()
                .mode(ArchitectMode.UPDATE)
                .outputSchemaType(OutputSchemaType.SCRIPT_JS)
                .existingScriptRef("scripts/missing.js")
                .status(ArchitectStatus.LOADING_EXISTING)
                .build();
        when(documentService.findByPath(any(), any(), any()))
                .thenReturn(Optional.empty());

        phase.execute(state, process, ctx);

        assertThat(state.getFailureReason())
                .isNotNull()
                .contains("not found")
                .contains("scripts/missing.js");
    }

    @Test
    void update_failsWhenDocumentIsEmpty() {
        ArchitectState state = ArchitectState.builder()
                .mode(ArchitectMode.UPDATE)
                .outputSchemaType(OutputSchemaType.SCRIPT_JS)
                .existingScriptRef("scripts/empty.js")
                .status(ArchitectStatus.LOADING_EXISTING)
                .build();
        DocumentDocument doc = new DocumentDocument();
        when(documentService.findByPath(any(), any(), any()))
                .thenReturn(Optional.of(doc));
        when(documentService.readContent(doc)).thenReturn("");

        phase.execute(state, process, ctx);

        assertThat(state.getFailureReason())
                .isNotNull()
                .contains("empty");
        assertThat(state.getExistingScriptCode()).isNull();
    }

    // ──────────────────── EDIT branch (regression) ────────────────────

    @Test
    void edit_loadsRecipeAndDetectsSchemaFromEngineField() {
        ArchitectState state = ArchitectState.builder()
                .mode(ArchitectMode.EDIT)
                .targetRecipeName("essay-pipeline")
                .status(ArchitectStatus.LOADING_EXISTING)
                .build();
        DocumentDocument doc = new DocumentDocument();
        String yaml = """
                name: essay-pipeline
                description: A test pipeline
                engine: marvin
                params:
                  rootTaskKind: PLAN
                promptPrefix: |
                  Do the thing.
                """;
        when(documentService.findByPath(
                eq(TENANT), eq(PROJECT),
                eq("_vance/recipes/_user/essay-pipeline.yaml")))
                .thenReturn(Optional.of(doc));
        when(documentService.readContent(doc)).thenReturn(yaml);

        phase.execute(state, process, ctx);

        assertThat(state.getFailureReason()).isNull();
        assertThat(state.getExistingRecipeYaml()).contains("engine: marvin");
        assertThat(state.getOutputSchemaType()).isEqualTo(OutputSchemaType.MARVIN_RECIPE);
        // EDIT doesn't touch UPDATE-only fields.
        assertThat(state.getExistingScriptCode()).isNull();
    }
}
