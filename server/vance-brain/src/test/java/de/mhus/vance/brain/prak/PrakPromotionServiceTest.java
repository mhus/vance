package de.mhus.vance.brain.prak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.memory.MemoryDocument;
import de.mhus.vance.shared.memory.MemoryKind;
import de.mhus.vance.shared.memory.MemoryService;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.prak.AffectsAction;
import de.mhus.vance.shared.prak.AffectsExisting;
import de.mhus.vance.shared.prak.Decay;
import de.mhus.vance.shared.prak.EvaluationOutput;
import de.mhus.vance.shared.prak.Evidence;
import de.mhus.vance.shared.prak.EvidenceRole;
import de.mhus.vance.shared.prak.ExtractedItem;
import de.mhus.vance.shared.prak.ItemType;
import de.mhus.vance.shared.prak.LongTermMemoryAction;
import de.mhus.vance.shared.prak.LongTermMemoryDecision;
import de.mhus.vance.shared.prak.Scope;
import de.mhus.vance.shared.prak.TargetRef;
import de.mhus.vance.shared.prak.WindowSpan;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PrakPromotionServiceTest {

    private MemoryService memoryService;
    private PrakPromotionService service;

    @BeforeEach
    void setUp() {
        memoryService = mock(MemoryService.class);
        when(memoryService.save(any())).thenAnswer(inv -> {
            MemoryDocument arg = inv.getArgument(0);
            arg.setId("mem-" + System.identityHashCode(arg));
            return arg;
        });
        service = new PrakPromotionService(
                memoryService,
                new MetricService(new SimpleMeterRegistry()));
    }

    // ─── default-action resolver ───

    @Test
    void resolveAction_importanceZeroAlwaysSkips() {
        ExtractedItem item = item("a", ItemType.FACT, 0,
                LongTermMemoryDecision.promote("force"));

        assertThat(PrakPromotionService.resolveAction(item))
                .isEqualTo(LongTermMemoryAction.SKIP);
    }

    @Test
    void resolveAction_instructionPromoteDowngradesToInboxOffer() {
        ExtractedItem item = item("a", ItemType.INSTRUCTION, 4,
                LongTermMemoryDecision.promote("analyzer wanted to promote"));

        assertThat(PrakPromotionService.resolveAction(item))
                .isEqualTo(LongTermMemoryAction.INBOX_OFFER);
    }

    @Test
    void resolveAction_factPromoteStays() {
        ExtractedItem item = item("a", ItemType.FACT, 3,
                LongTermMemoryDecision.promote("ok"));

        assertThat(PrakPromotionService.resolveAction(item))
                .isEqualTo(LongTermMemoryAction.PROMOTE);
    }

    @Test
    void resolveAction_instructionSkipStays() {
        // Punctual session-scope instruction — analyzer chose SKIP, must respect.
        ExtractedItem item = item("a", ItemType.INSTRUCTION, 4,
                LongTermMemoryDecision.skip("session scope"));

        assertThat(PrakPromotionService.resolveAction(item))
                .isEqualTo(LongTermMemoryAction.SKIP);
    }

    @Test
    void resolveAction_instructionInboxOfferStays() {
        ExtractedItem item = item("a", ItemType.INSTRUCTION, 4,
                LongTermMemoryDecision.inboxOffer("normal path"));

        assertThat(PrakPromotionService.resolveAction(item))
                .isEqualTo(LongTermMemoryAction.INBOX_OFFER);
    }

    // ─── promote ───

    @Test
    void promote_persistsFactAsInsightMemory() {
        ExtractedItem fact = item("evt-1", ItemType.FACT, 3,
                LongTermMemoryDecision.promote("project observation"));

        PromotionResult res = service.promote(
                output(fact),
                new PromotionContext("t", "p", "s", "proc", "run-42"));

        assertThat(res.promoted()).isEqualTo(1);
        assertThat(res.persistedMemoryIds()).hasSize(1);

        ArgumentCaptor<MemoryDocument> cap = ArgumentCaptor.forClass(MemoryDocument.class);
        verify(memoryService).save(cap.capture());
        MemoryDocument saved = cap.getValue();
        assertThat(saved.getKind()).isEqualTo(MemoryKind.INSIGHT);
        assertThat(saved.getTenantId()).isEqualTo("t");
        assertThat(saved.getProjectId()).isEqualTo("p");
        assertThat(saved.getTitle()).startsWith("fact:");
        assertThat(saved.getMetadata())
                .containsEntry(PrakPromotionService.META_GENERATED_BY, "prak")
                .containsEntry(PrakPromotionService.META_PRAK_ITEM_ID, "evt-1")
                .containsEntry(PrakPromotionService.META_PRAK_RUN_ID, "run-42")
                .containsEntry(PrakPromotionService.META_ITEM_TYPE, "fact")
                .containsEntry(PrakPromotionService.META_IMPORTANCE, 3);
    }

    @Test
    void promote_factScopeOverridesContextProjectId() {
        // Analyzer chose project "x" via scope; ctx default "p" — scope wins.
        ExtractedItem item = factInScope("evt-1", Scope.project("x"));

        service.promote(
                output(item),
                new PromotionContext("t", "p", null, null, "run"));

        ArgumentCaptor<MemoryDocument> cap = ArgumentCaptor.forClass(MemoryDocument.class);
        verify(memoryService).save(cap.capture());
        assertThat(cap.getValue().getProjectId()).isEqualTo("x");
    }

    @Test
    void promote_globalScopeWritesEmptyProjectId() {
        ExtractedItem item = factInScope("evt-1", Scope.global());

        service.promote(
                output(item),
                new PromotionContext("t", "p", null, null, "run"));

        ArgumentCaptor<MemoryDocument> cap = ArgumentCaptor.forClass(MemoryDocument.class);
        verify(memoryService).save(cap.capture());
        assertThat(cap.getValue().getProjectId()).isEqualTo("");
        assertThat(cap.getValue().getSessionId()).isNull();
    }

    @Test
    void promote_sessionScopePinsSessionId() {
        ExtractedItem item = factInScope("evt-1", Scope.session("sess-99"));

        service.promote(
                output(item),
                new PromotionContext("t", "p", null, null, "run"));

        ArgumentCaptor<MemoryDocument> cap = ArgumentCaptor.forClass(MemoryDocument.class);
        verify(memoryService).save(cap.capture());
        assertThat(cap.getValue().getSessionId()).isEqualTo("sess-99");
        assertThat(cap.getValue().getProjectId()).isEqualTo("p");
    }

    @Test
    void promote_evidenceTurnIdsBecomeSourceRefs() {
        ExtractedItem item = new ExtractedItem(
                "evt-1", ItemType.FACT, 3, "Codebase uses MySQL",
                Scope.project("p"), 0.9, List.of("tech-stack"),
                List.of(
                        new Evidence("msg-A", EvidenceRole.USER, null),
                        new Evidence("msg-B", EvidenceRole.ASSISTANT, "cite"),
                        new Evidence("  ", EvidenceRole.USER, null)),  // blank dropped
                null, Decay.SLOW,
                LongTermMemoryDecision.promote("ok"),
                List.<AffectsExisting>of());

        service.promote(
                output(item),
                new PromotionContext("t", "p", null, null, "run"));

        ArgumentCaptor<MemoryDocument> cap = ArgumentCaptor.forClass(MemoryDocument.class);
        verify(memoryService).save(cap.capture());
        assertThat(cap.getValue().getSourceRefs())
                .containsExactly("msg-A", "msg-B");
    }

    @Test
    void promote_truncatesLongContentInTitle() {
        String longContent = "X".repeat(200);
        ExtractedItem item = new ExtractedItem(
                "evt-1", ItemType.FACT, 3, longContent,
                Scope.project("p"), 0.9, List.of(), List.of(),
                null, Decay.SLOW,
                LongTermMemoryDecision.promote("ok"),
                List.<AffectsExisting>of());

        service.promote(
                output(item),
                new PromotionContext("t", "p", null, null, "run"));

        ArgumentCaptor<MemoryDocument> cap = ArgumentCaptor.forClass(MemoryDocument.class);
        verify(memoryService).save(cap.capture());
        assertThat(cap.getValue().getTitle()).hasSizeLessThanOrEqualTo(
                PrakPromotionService.TITLE_MAX_LEN);
        assertThat(cap.getValue().getTitle()).endsWith("…");
        assertThat(cap.getValue().getContent()).hasSize(longContent.length());
    }

    // ─── inboxOffer / refresh / skip ───

    @Test
    void promote_instructionRoutesToInboxOfferCounter() {
        ExtractedItem item = item("evt-1", ItemType.INSTRUCTION, 4,
                LongTermMemoryDecision.inboxOffer("user must confirm"));

        PromotionResult res = service.promote(
                output(item),
                new PromotionContext("t", "p", null, null, "run"));

        assertThat(res.inboxOffered()).isEqualTo(1);
        assertThat(res.promoted()).isZero();
        verifyNoInteractions(memoryService);
    }

    @Test
    void promote_instructionPromoteDowngradesToInboxOffer() {
        ExtractedItem item = item("evt-1", ItemType.INSTRUCTION, 4,
                LongTermMemoryDecision.promote("analyzer slip"));

        PromotionResult res = service.promote(
                output(item),
                new PromotionContext("t", "p", null, null, "run"));

        assertThat(res.inboxOffered()).isEqualTo(1);
        assertThat(res.promoted()).isZero();
        verifyNoInteractions(memoryService);
    }

    @Test
    void promote_importanceZeroSkipsEvenIfAnalyzerSaidPromote() {
        ExtractedItem item = item("evt-1", ItemType.FACT, 0,
                LongTermMemoryDecision.promote("oops"));

        PromotionResult res = service.promote(
                output(item),
                new PromotionContext("t", "p", null, null, "run"));

        assertThat(res.skipped()).isEqualTo(1);
        assertThat(res.promoted()).isZero();
        verifyNoInteractions(memoryService);
    }

    @Test
    void promote_refreshRoutesToRefreshCounter() {
        ExtractedItem item = item("evt-1", ItemType.FACT, 3,
                LongTermMemoryDecision.refresh("rediscovered"));

        PromotionResult res = service.promote(
                output(item),
                new PromotionContext("t", "p", null, null, "run"));

        assertThat(res.refreshed()).isEqualTo(1);
        verifyNoInteractions(memoryService);
    }

    // ─── affectsExisting deferred ───

    @Test
    void promote_recordsAffectsExistingAsDeferred() {
        AffectsExisting affects = new AffectsExisting(
                AffectsAction.SUPERSEDE,
                TargetRef.byLabels(List.of("git-workflow", "commit"), 2),
                "replaces earlier rule");

        ExtractedItem item = new ExtractedItem(
                "evt-1", ItemType.INSTRUCTION, 4, "Never commit",
                Scope.project("p"), 0.92, List.of("git-workflow"),
                List.of(), null, Decay.SLOW,
                LongTermMemoryDecision.inboxOffer("user must confirm"),
                List.of(affects));

        PromotionResult res = service.promote(
                output(item),
                new PromotionContext("t", "p", null, null, "run"));

        assertThat(res.affectsDeferred()).isEqualTo(1);
        assertThat(res.affectsResolved()).isZero();
    }

    // ─── edge cases ───

    @Test
    void promote_emptyEvaluationReturnsEmptyResult() {
        PromotionResult res = service.promote(
                EvaluationOutput.empty(new WindowSpan(null, null, 0)),
                new PromotionContext("t", "p", null, null, "run"));

        assertThat(res.promoted()).isZero();
        assertThat(res.inboxOffered()).isZero();
        assertThat(res.persistedMemoryIds()).isEmpty();
        verifyNoInteractions(memoryService);
    }

    @Test
    void promote_savesFailureCountsAsSkipped() {
        // Use doThrow / when so the previous setUp stub's answer-lambda
        // isn't invoked while re-stubbing (which would NPE on the null
        // matcher argument).
        org.mockito.Mockito.doThrow(new RuntimeException("mongo gone"))
                .when(memoryService).save(any());

        ExtractedItem item = item("evt-1", ItemType.FACT, 3,
                LongTermMemoryDecision.promote("ok"));

        PromotionResult res = service.promote(
                output(item),
                new PromotionContext("t", "p", null, null, "run"));

        assertThat(res.promoted()).isZero();
        assertThat(res.skipped()).isEqualTo(1);
    }

    // ─── factories ───

    private static ExtractedItem item(
            String id, ItemType type, int importance,
            LongTermMemoryDecision decision) {
        return new ExtractedItem(
                id, type, importance, "content " + id,
                Scope.project("p"), 0.9, List.of("label"),
                List.of(new Evidence("msg-1", EvidenceRole.USER, null)),
                null, Decay.SLOW, decision,
                List.<AffectsExisting>of());
    }

    private static ExtractedItem factInScope(String id, Scope scope) {
        return new ExtractedItem(
                id, ItemType.FACT, 3, "content " + id,
                scope, 0.9, List.of("label"),
                List.of(new Evidence("msg-1", EvidenceRole.USER, null)),
                null, Decay.SLOW,
                LongTermMemoryDecision.promote("ok"),
                List.<AffectsExisting>of());
    }

    private static EvaluationOutput output(ExtractedItem... items) {
        return new EvaluationOutput(
                new WindowSpan("from", "to", items.length),
                List.of(items), List.of());
    }
}
