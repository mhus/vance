package de.mhus.vance.brain.prak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.shared.prak.AffectsAction;
import de.mhus.vance.shared.prak.CrossItemRelationType;
import de.mhus.vance.shared.prak.EvaluationOutput;
import de.mhus.vance.shared.prak.EvidenceRole;
import de.mhus.vance.shared.prak.ItemCountExpectation;
import de.mhus.vance.shared.prak.ItemType;
import de.mhus.vance.shared.prak.LongTermMemoryAction;
import de.mhus.vance.shared.prak.ScopeKind;
import de.mhus.vance.shared.prak.TargetRefKind;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PrakServiceTest {

    private LightLlmService lightLlm;
    private PrakService analyzer;

    @BeforeEach
    void setUp() {
        lightLlm = mock(LightLlmService.class);
        analyzer = new PrakService(lightLlm);
    }

    @Test
    void analyze_blankTenantThrows() {
        assertThatThrownBy(() -> analyzer.analyze(
                "", null, null, List.of(user("foo")), "test", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
        verifyNoInteractions(lightLlm);
    }

    @Test
    void analyze_emptyMessagesReturnsEmptyOutputWithoutLlmCall() {
        EvaluationOutput out = analyzer.analyze(
                "t1", "p1", "proc1", List.of(), "test", null);

        assertThat(out.items()).isEmpty();
        assertThat(out.crossItemRelations()).isEmpty();
        assertThat(out.windowSpan().messagesAnalyzed()).isZero();
        verifyNoInteractions(lightLlm);
    }

    @Test
    void analyze_callsLightLlmWithRecipeAndPebbleVars() {
        when(lightLlm.callForJson(any())).thenReturn(Map.of("items", List.of()));

        analyzer.analyze(
                "tenant-X",
                "project-Y",
                "process-Z",
                List.of(user("foo bar baz"), assistant("ack")),
                "compaction-side-channel",
                ItemCountExpectation.NORMAL);

        ArgumentCaptor<LightLlmRequest> captor =
                ArgumentCaptor.forClass(LightLlmRequest.class);
        verify(lightLlm, times(1)).callForJson(captor.capture());

        LightLlmRequest req = captor.getValue();
        assertThat(req.getRecipeName()).isEqualTo(PrakService.DEFAULT_RECIPE_NAME);
        assertThat(req.getTenantId()).isEqualTo("tenant-X");
        assertThat(req.getProjectId()).isEqualTo("project-Y");
        assertThat(req.getProcessId()).isEqualTo("process-Z");
        assertThat(req.getSchema()).containsKey("required");
        assertThat(req.getPebbleVars()).containsKeys(
                PrakService.VAR_MESSAGES,
                PrakService.VAR_WINDOW_HINT,
                PrakService.VAR_EXPECTED_ITEMS_HINT);
        assertThat(req.getPebbleVars().get(PrakService.VAR_WINDOW_HINT))
                .isEqualTo("compaction-side-channel");
        assertThat((String) req.getPebbleVars().get(PrakService.VAR_EXPECTED_ITEMS_HINT))
                .contains("1-5");
    }

    @Test
    void analyze_suppressesExpectationHintWhenNull() {
        when(lightLlm.callForJson(any())).thenReturn(Map.of("items", List.of()));

        analyzer.analyze("t", null, null,
                List.of(user("foo bar baz")), "audit-tag", null);

        ArgumentCaptor<LightLlmRequest> captor =
                ArgumentCaptor.forClass(LightLlmRequest.class);
        verify(lightLlm).callForJson(captor.capture());
        assertThat((String) captor.getValue().getPebbleVars()
                .get(PrakService.VAR_EXPECTED_ITEMS_HINT))
                .isEqualTo("(no estimate)");
    }

    // ---- mapping ----

    @Test
    void analyze_mapsCompleteItem() {
        java.util.LinkedHashMap<String, Object> itemMap = new java.util.LinkedHashMap<>();
        itemMap.put("id", "evt-1");
        itemMap.put("type", "instruction");
        itemMap.put("importance", 4);
        itemMap.put("content", "Never commit without asking");
        itemMap.put("scope", Map.of("kind", "project", "id", "vance-wb"));
        itemMap.put("confidence", 0.92);
        itemMap.put("labels", List.of("git-workflow", "commit-policy"));
        itemMap.put("evidence", List.of(Map.of(
                "turnId", "msg-A",
                "role", "user",
                "snippet", "ab jetzt nicht mehr committen")));
        itemMap.put("why", "Imperative mit Zeit-Anker");
        itemMap.put("decay", "slow");
        itemMap.put("longTermMemory", Map.of(
                "action", "inboxOffer",
                "rationale", "Pflicht-Confirm fuer Instructions"));
        itemMap.put("affectsExisting", List.of());

        when(lightLlm.callForJson(any())).thenReturn(Map.of("items", List.of(itemMap)));

        EvaluationOutput out = analyzer.analyze(
                "t", null, null, List.of(user("u")), "test", null);

        assertThat(out.items()).hasSize(1);
        var item = out.items().get(0);
        assertThat(item.id()).isEqualTo("evt-1");
        assertThat(item.type()).isEqualTo(ItemType.INSTRUCTION);
        assertThat(item.importance()).isEqualTo(4);
        assertThat(item.content()).isEqualTo("Never commit without asking");
        assertThat(item.scope().kind()).isEqualTo(ScopeKind.PROJECT);
        assertThat(item.scope().id()).isEqualTo("vance-wb");
        assertThat(item.confidence()).isEqualTo(0.92);
        assertThat(item.labels()).containsExactly("git-workflow", "commit-policy");
        assertThat(item.evidence()).hasSize(1);
        assertThat(item.evidence().get(0).turnId()).isEqualTo("msg-A");
        assertThat(item.evidence().get(0).role()).isEqualTo(EvidenceRole.USER);
        assertThat(item.longTermMemory().action()).isEqualTo(LongTermMemoryAction.INBOX_OFFER);
    }

    @Test
    void analyze_acceptsBothSnakeAndCamelInboxOffer() {
        when(lightLlm.callForJson(any())).thenReturn(Map.of(
                "items", List.of(
                        item("a", "fact", "X", Map.of("kind", "project"), 0.9,
                                "promote"),
                        item("b", "instruction", "Y", Map.of("kind", "project"), 0.9,
                                "inbox_offer"),
                        item("c", "instruction", "Z", Map.of("kind", "project"), 0.9,
                                "inboxOffer"))));

        EvaluationOutput out = analyzer.analyze(
                "t", null, null, List.of(user("u")), "test", null);

        assertThat(out.items()).extracting(i -> i.longTermMemory().action())
                .containsExactly(
                        LongTermMemoryAction.PROMOTE,
                        LongTermMemoryAction.INBOX_OFFER,
                        LongTermMemoryAction.INBOX_OFFER);
    }

    @Test
    void analyze_skipsItemsWithoutContent() {
        when(lightLlm.callForJson(any())).thenReturn(Map.of(
                "items", List.of(
                        item("a", "fact", "real content", Map.of("kind", "project"), 0.9, "promote"),
                        item("b", "fact", "", Map.of("kind", "project"), 0.9, "promote"),
                        Map.of("type", "fact"))));

        EvaluationOutput out = analyzer.analyze(
                "t", null, null, List.of(user("u")), "test", null);

        assertThat(out.items()).hasSize(1);
        assertThat(out.items().get(0).id()).isEqualTo("a");
    }

    @Test
    void analyze_assignsAutoIdsForMissingOrDuplicate() {
        when(lightLlm.callForJson(any())).thenReturn(Map.of(
                "items", List.of(
                        item(null, "fact", "X", Map.of("kind", "project"), 0.9, "promote"),
                        item("dup", "fact", "Y", Map.of("kind", "project"), 0.9, "promote"),
                        item("dup", "fact", "Z", Map.of("kind", "project"), 0.9, "promote"))));

        EvaluationOutput out = analyzer.analyze(
                "t", null, null, List.of(user("u")), "test", null);

        assertThat(out.items()).hasSize(3);
        // First missing-id → auto-id; third duplicate also auto-id.
        assertThat(out.items().get(0).id()).startsWith("evt-auto-");
        assertThat(out.items().get(1).id()).isEqualTo("dup");
        assertThat(out.items().get(2).id()).startsWith("evt-auto-");
        assertThat(out.items().stream().map(i -> i.id()).distinct()).hasSize(3);
    }

    @Test
    void analyze_clampsImportanceAndConfidence() {
        when(lightLlm.callForJson(any())).thenReturn(Map.of(
                "items", List.of(
                        item("a", "fact", "X", Map.of("kind", "project"),
                                999.0, "promote", -3),
                        item("b", "fact", "Y", Map.of("kind", "project"),
                                -1.0, "promote", 9))));

        EvaluationOutput out = analyzer.analyze(
                "t", null, null, List.of(user("u")), "test", null);

        assertThat(out.items().get(0).confidence()).isEqualTo(1.0);
        assertThat(out.items().get(0).importance()).isEqualTo(0);
        assertThat(out.items().get(1).confidence()).isEqualTo(0.0);
        assertThat(out.items().get(1).importance()).isEqualTo(5);
    }

    @Test
    void analyze_appliesDefaultsForMissingFields() {
        when(lightLlm.callForJson(any())).thenReturn(Map.of(
                "items", List.of(Map.of("content", "Just a statement"))));

        EvaluationOutput out = analyzer.analyze(
                "t", null, null, List.of(user("u")), "test", null);

        assertThat(out.items()).hasSize(1);
        var item = out.items().get(0);
        assertThat(item.type()).isEqualTo(ItemType.FACT);
        assertThat(item.scope().kind()).isEqualTo(ScopeKind.GLOBAL);
        assertThat(item.evidence()).isEmpty();
        assertThat(item.labels()).isEmpty();
        // No decision → SKIP fallback so we don't accidentally promote noise
        assertThat(item.longTermMemory().action()).isEqualTo(LongTermMemoryAction.SKIP);
    }

    @Test
    void analyze_mapsCrossItemRelationsAcceptingMultipleSpellings() {
        when(lightLlm.callForJson(any())).thenReturn(Map.of(
                "items", List.of(
                        item("a", "fact", "X", Map.of("kind", "project"), 0.9, "promote"),
                        item("b", "fact", "Y", Map.of("kind", "project"), 0.9, "promote"),
                        item("c", "fact", "Z", Map.of("kind", "project"), 0.9, "promote")),
                "crossItemRelations", List.of(
                        Map.of("from", "a", "to", "b", "relation", "supersedesWithinBatch"),
                        Map.of("from", "b", "to", "c", "relation", "extends-within-batch"))));

        EvaluationOutput out = analyzer.analyze(
                "t", null, null, List.of(user("u")), "test", null);

        assertThat(out.crossItemRelations()).hasSize(2);
        assertThat(out.crossItemRelations().get(0).relation())
                .isEqualTo(CrossItemRelationType.SUPERSEDES_WITHIN_BATCH);
        assertThat(out.crossItemRelations().get(1).relation())
                .isEqualTo(CrossItemRelationType.EXTENDS_WITHIN_BATCH);
    }

    @Test
    void analyze_mapsAffectsExistingWithLabelsTargetRef() {
        java.util.LinkedHashMap<String, Object> itemMap = new java.util.LinkedHashMap<>();
        itemMap.put("id", "evt-1");
        itemMap.put("type", "instruction");
        itemMap.put("importance", 4);
        itemMap.put("content", "Never commit");
        itemMap.put("scope", Map.of("kind", "project"));
        itemMap.put("confidence", 0.9);
        itemMap.put("labels", List.of("git-workflow"));
        itemMap.put("evidence", List.of());
        itemMap.put("decay", "slow");
        itemMap.put("longTermMemory", Map.of("action", "promote"));
        itemMap.put("affectsExisting", List.of(Map.of(
                "action", "supersede",
                "targetRef", Map.of(
                        "kind", "labels",
                        "labels", List.of("git-workflow", "commit"),
                        "minOverlap", 2),
                "rationale", "replaces earlier rule")));

        when(lightLlm.callForJson(any())).thenReturn(Map.of("items", List.of(itemMap)));

        EvaluationOutput out = analyzer.analyze(
                "t", null, null, List.of(user("u")), "test", null);

        var affects = out.items().get(0).affectsExisting();
        assertThat(affects).hasSize(1);
        assertThat(affects.get(0).action()).isEqualTo(AffectsAction.SUPERSEDE);
        assertThat(affects.get(0).targetRef().kind()).isEqualTo(TargetRefKind.LABELS);
        assertThat(affects.get(0).targetRef().labels())
                .containsExactly("git-workflow", "commit");
        assertThat(affects.get(0).targetRef().minOverlap()).isEqualTo(2);
    }

    @Test
    void analyze_buildsWindowSpanFromMessageIds() {
        when(lightLlm.callForJson(any())).thenReturn(Map.of("items", List.of()));

        EvaluationOutput out = analyzer.analyze(
                "t", null, null,
                List.of(
                        new SpanMessage("first-id", ChatRole.USER, "hi"),
                        new SpanMessage("second-id", ChatRole.ASSISTANT, "ack"),
                        new SpanMessage("third-id", ChatRole.USER, "more")),
                "test", null);

        assertThat(out.windowSpan().fromTurnId()).isEqualTo("first-id");
        assertThat(out.windowSpan().toTurnId()).isEqualTo("third-id");
        assertThat(out.windowSpan().messagesAnalyzed()).isEqualTo(3);
    }

    // ---- renderMessages ----

    @Test
    void renderMessages_formatsWithTurnIdAndRole() {
        String rendered = PrakService.renderMessages(List.of(
                new SpanMessage("msg-42", ChatRole.USER, "schau dir foo.java an"),
                new SpanMessage("msg-43", ChatRole.ASSISTANT, "ok schau ich")));

        assertThat(rendered)
                .contains("[msg-42 · user]")
                .contains("schau dir foo.java an")
                .contains("[msg-43 · assistant]")
                .contains("ok schau ich");
    }

    @Test
    void renderMessages_fallbacksToPositionIdWhenMessageIdNull() {
        String rendered = PrakService.renderMessages(List.of(
                new SpanMessage(null, ChatRole.USER, "synthetic")));

        assertThat(rendered).contains("[msg-0 · user]");
    }

    // ---- factories ----

    private static SpanMessage user(String content) {
        return new SpanMessage(null, ChatRole.USER, content);
    }

    private static SpanMessage assistant(String content) {
        return new SpanMessage(null, ChatRole.ASSISTANT, content);
    }

    /** Compact item map factory — uses sensible defaults for what we don't care about. */
    private static Map<String, Object> item(
            @org.jspecify.annotations.Nullable String id,
            String type, String content,
            Map<String, Object> scope, double confidence, String action) {
        return item(id, type, content, scope, confidence, action, 3);
    }

    private static Map<String, Object> item(
            @org.jspecify.annotations.Nullable String id,
            String type, String content,
            Map<String, Object> scope, double confidence, String action,
            Object importance) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        if (id != null) m.put("id", id);
        m.put("type", type);
        m.put("importance", importance);
        m.put("content", content);
        m.put("scope", scope);
        m.put("confidence", confidence);
        m.put("labels", List.of());
        m.put("evidence", List.of());
        m.put("decay", "slow");
        m.put("longTermMemory", Map.of("action", action));
        m.put("affectsExisting", List.of());
        return m;
    }
}
