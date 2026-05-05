package de.mhus.vance.brain.slartibartfast.phases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.slartibartfast.ArchitectState;
import de.mhus.vance.api.slartibartfast.ArchitectStatus;
import de.mhus.vance.api.slartibartfast.EvidenceSource;
import de.mhus.vance.api.slartibartfast.EvidenceType;
import de.mhus.vance.api.slartibartfast.PhaseIteration;
import de.mhus.vance.api.slartibartfast.Rationale;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GatheringPhase}. Mocks
 * {@link DocumentService} so the test stays in-memory; verifies
 * the manual-prefix filter, content read, audit append and
 * idempotent re-execution behaviour.
 */
class GatheringPhaseTest {

    private DocumentService documentService;
    private GatheringPhase phase;

    private ThinkProcessDocument process;
    private ThinkEngineContext ctx;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        phase = new GatheringPhase(documentService);

        process = new ThinkProcessDocument();
        process.setId("proc-1");
        process.setTenantId("acme");
        process.setProjectId("test-project");
        process.setSessionId("sess-1");
        ctx = mock(ThinkEngineContext.class);

        // Default: loadContent reads the inline text via
        // ByteArrayInputStream — same path the real service uses
        // for inline documents.
        when(documentService.loadContent(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    DocumentDocument d = inv.getArgument(0);
                    String inline = d.getInlineText() == null
                            ? "" : d.getInlineText();
                    return new ByteArrayInputStream(
                            inline.getBytes(StandardCharsets.UTF_8));
                });
    }

    @Test
    void readsAllManualsUnderManualsPrefix_inAlphabeticalOrder() {
        when(documentService.listByProject("acme", "test-project"))
                .thenReturn(List.of(
                        manual("manuals/essay/STYLE.md", "Adams style content"),
                        manual("recipes/some.yaml", "should be skipped"),
                        manual("manuals/essay/STRUCTURE.md", "structure content"),
                        manual("essay/draft.md", "should be skipped")));

        ArchitectState state = ArchitectState.builder().runId("run1").build();
        phase.execute(state, process, ctx);

        // Alphabetical by path → STRUCTURE.md (S-T) before STYLE.md (S-T-Y).
        assertThat(state.getEvidenceSources())
                .extracting(EvidenceSource::getPath)
                .containsExactly("manuals/essay/STRUCTURE.md",
                        "manuals/essay/STYLE.md");
        assertThat(state.getEvidenceSources())
                .extracting(EvidenceSource::getType)
                .containsOnly(EvidenceType.MANUAL);
        assertThat(state.getEvidenceSources())
                .extracting(EvidenceSource::getId)
                .containsExactly("ev1", "ev2");
    }

    @Test
    void everySourceCarriesGatheringRationale_resolvableInPool() {
        when(documentService.listByProject("acme", "test-project"))
                .thenReturn(List.of(manual("manuals/x.md", "content")));

        ArchitectState state = ArchitectState.builder().runId("run1").build();
        phase.execute(state, process, ctx);

        EvidenceSource source = state.getEvidenceSources().get(0);
        assertThat(source.getGatheringRationaleId()).isNotNull();
        assertThat(state.getRationales())
                .anySatisfy(r -> {
                    assertThat(r.getId()).isEqualTo(source.getGatheringRationaleId());
                    assertThat(r.getInferredAt())
                            .isEqualTo(ArchitectStatus.GATHERING);
                    assertThat(r.getSourceRefs()).contains(source.getId());
                });
    }

    @Test
    void readsContent_intoEvidenceSourceVerbatim() {
        String body = "# heading\nbody line 1\nbody line 2";
        when(documentService.listByProject("acme", "test-project"))
                .thenReturn(List.of(manual("manuals/h.md", body)));

        ArchitectState state = ArchitectState.builder().runId("run1").build();
        phase.execute(state, process, ctx);

        assertThat(state.getEvidenceSources().get(0).getContent()).isEqualTo(body);
    }

    @Test
    void noManuals_yieldsEmptyEvidenceSourcesList_butStillAppendsIteration() {
        when(documentService.listByProject("acme", "test-project"))
                .thenReturn(List.of(manual("recipes/foo.yaml", "x")));

        ArchitectState state = ArchitectState.builder().runId("run1").build();
        phase.execute(state, process, ctx);

        assertThat(state.getEvidenceSources()).isEmpty();
        assertThat(state.getIterations())
                .filteredOn(it -> it.getPhase() == ArchitectStatus.GATHERING)
                .hasSize(1);
        assertThat(state.getIterations().get(0).getOutcome())
                .isEqualTo(PhaseIteration.IterationOutcome.PASSED);
    }

    @Test
    void reExecute_rebuildsEvidenceSourcesFromScratch() {
        when(documentService.listByProject("acme", "test-project"))
                .thenReturn(List.of(manual("manuals/a.md", "first")));

        ArchitectState state = ArchitectState.builder().runId("run1").build();
        phase.execute(state, process, ctx);
        assertThat(state.getEvidenceSources()).hasSize(1);

        // Second pass with a different manual set — the new gather
        // must replace the old one, not extend it.
        when(documentService.listByProject("acme", "test-project"))
                .thenReturn(List.of(
                        manual("manuals/b.md", "second"),
                        manual("manuals/c.md", "third")));
        phase.execute(state, process, ctx);

        assertThat(state.getEvidenceSources())
                .extracting(EvidenceSource::getPath)
                .containsExactly("manuals/b.md", "manuals/c.md");

        // Two iteration entries — initial + recovery.
        assertThat(state.getIterations())
                .filteredOn(it -> it.getPhase() == ArchitectStatus.GATHERING)
                .hasSize(2);
        assertThat(state.getIterations())
                .filteredOn(it -> it.getPhase() == ArchitectStatus.GATHERING)
                .extracting(PhaseIteration::getTriggeredBy)
                .containsExactly("initial", "recovery");
    }

    @Test
    void rationalesAccumulate_acrossPhases_butGatheringEntriesAreFresh() {
        // Pre-populate the pool with a Rationale from a (fictional)
        // earlier FRAMING pass — GatheringPhase must not erase it.
        ArchitectState state = ArchitectState.builder()
                .runId("run1")
                .rationales(new java.util.ArrayList<>(List.of(
                        Rationale.builder()
                                .id("rt-pre")
                                .text("from FRAMING")
                                .inferredAt(ArchitectStatus.FRAMING)
                                .build())))
                .build();
        when(documentService.listByProject("acme", "test-project"))
                .thenReturn(List.of(manual("manuals/x.md", "content")));

        phase.execute(state, process, ctx);

        assertThat(state.getRationales())
                .extracting(Rationale::getId)
                .contains("rt-pre")
                .anySatisfy(id -> assertThat(id).startsWith("rt"));
    }

    private static DocumentDocument manual(String path, String inline) {
        DocumentDocument d = new DocumentDocument();
        d.setPath(path);
        d.setInlineText(inline);
        return d;
    }
}
