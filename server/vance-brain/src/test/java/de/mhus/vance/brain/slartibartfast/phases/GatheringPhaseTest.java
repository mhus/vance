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

        // Default: loadContent reads from a per-test in-memory body map keyed
        // by storageId. Tests populate the map via the {@link #manual} helper.
        when(documentService.loadContent(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    DocumentDocument d = inv.getArgument(0);
                    String body = bodyByStorageId.getOrDefault(d.getStorageId(), "");
                    return new ByteArrayInputStream(
                            body.getBytes(StandardCharsets.UTF_8));
                });
    }

    @Test
    void readsAllManualsUnderManualsPrefix_inAlphabeticalOrder() {
        when(documentService.listByProject("acme", "test-project"))
                .thenReturn(List.of(
                        manual("_vance/manuals/essay/STYLE.md", "Adams style content"),
                        manual("recipes/some.yaml", "should be skipped"),
                        manual("_vance/manuals/essay/STRUCTURE.md", "structure content"),
                        manual("essay/draft.md", "should be skipped")));

        ArchitectState state = ArchitectState.builder().runId("run1").build();
        phase.execute(state, process, ctx);

        // Alphabetical by path → STRUCTURE.md (S-T) before STYLE.md (S-T-Y).
        // Project manuals first, then bundled engine self-knowledge for
        // the active schema (default VOGON_STRATEGY → vogon-architect/).
        assertThat(state.getEvidenceSources())
                .extracting(EvidenceSource::getPath)
                .containsSequence("_vance/manuals/essay/STRUCTURE.md",
                        "_vance/manuals/essay/STYLE.md");
        assertThat(state.getEvidenceSources())
                .extracting(EvidenceSource::getType)
                .containsOnly(EvidenceType.MANUAL);
        assertThat(state.getEvidenceSources())
                .as("bundled engine self-knowledge appended after project manuals")
                .anySatisfy(s -> assertThat(s.getPath())
                        .startsWith("vance-defaults/_vance/manuals/slartibartfast/"));
    }

    @Test
    void everySourceCarriesGatheringRationale_resolvableInPool() {
        when(documentService.listByProject("acme", "test-project"))
                .thenReturn(List.of(manual("_vance/manuals/x.md", "content")));

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
                .thenReturn(List.of(manual("_vance/manuals/h.md", body)));

        ArchitectState state = ArchitectState.builder().runId("run1").build();
        phase.execute(state, process, ctx);

        assertThat(state.getEvidenceSources().get(0).getContent()).isEqualTo(body);
    }

    @Test
    void noProjectManuals_stillEmitsBundledEngineSelfKnowledge() {
        when(documentService.listByProject("acme", "test-project"))
                .thenReturn(List.of(manual("recipes/foo.yaml", "x")));

        ArchitectState state = ArchitectState.builder().runId("run1").build();
        phase.execute(state, process, ctx);

        // Greenfield project (no manuals/ folder) still gets the
        // bundled engine-self-knowledge so DECOMPOSING has evidence
        // to anchor subgoals against. That is variant-C of the
        // "Slart without an installed kit" fix.
        assertThat(state.getEvidenceSources())
                .as("bundled self-knowledge must be loaded even without project manuals")
                .isNotEmpty()
                .allSatisfy(s -> assertThat(s.getPath())
                        .startsWith("vance-defaults/_vance/manuals/slartibartfast/"));
        assertThat(state.getIterations())
                .filteredOn(it -> it.getPhase() == ArchitectStatus.GATHERING)
                .hasSize(1);
        assertThat(state.getIterations().get(0).getOutcome())
                .isEqualTo(PhaseIteration.IterationOutcome.PASSED);
    }

    @Test
    void reExecute_rebuildsEvidenceSourcesFromScratch() {
        when(documentService.listByProject("acme", "test-project"))
                .thenReturn(List.of(manual("_vance/manuals/a.md", "first")));

        ArchitectState state = ArchitectState.builder().runId("run1").build();
        phase.execute(state, process, ctx);
        // 1 project manual + bundled self-knowledge for default schema.
        assertThat(state.getEvidenceSources()).hasSizeGreaterThanOrEqualTo(2);

        // Second pass with a different manual set — the new gather
        // must replace the old one, not extend it.
        when(documentService.listByProject("acme", "test-project"))
                .thenReturn(List.of(
                        manual("_vance/manuals/b.md", "second"),
                        manual("_vance/manuals/c.md", "third")));
        phase.execute(state, process, ctx);

        // Project manuals first (in alpha order), bundled appended.
        assertThat(state.getEvidenceSources())
                .extracting(EvidenceSource::getPath)
                .containsSequence("_vance/manuals/b.md", "_vance/manuals/c.md");
        // Old "_vance/manuals/a.md" from the first pass must be gone.
        assertThat(state.getEvidenceSources())
                .extracting(EvidenceSource::getPath)
                .doesNotContain("_vance/manuals/a.md");

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
                .thenReturn(List.of(manual("_vance/manuals/x.md", "content")));

        phase.execute(state, process, ctx);

        assertThat(state.getRationales())
                .extracting(Rationale::getId)
                .contains("rt-pre")
                .anySatisfy(id -> assertThat(id).startsWith("rt"));
    }

    private DocumentDocument manual(String path, String inline) {
        DocumentDocument d = new DocumentDocument();
        d.setPath(path);
        String sid = "blob-" + path;
        d.setStorageId(sid);
        bodyByStorageId.put(sid, inline);
        return d;
    }

    private final java.util.Map<String, String> bodyByStorageId = new java.util.HashMap<>();
}
