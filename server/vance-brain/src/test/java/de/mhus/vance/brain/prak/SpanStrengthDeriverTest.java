package de.mhus.vance.brain.prak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.prak.AffectsExisting;
import de.mhus.vance.shared.prak.Decay;
import de.mhus.vance.shared.prak.EvaluationOutput;
import de.mhus.vance.shared.prak.Evidence;
import de.mhus.vance.shared.prak.EvidenceRole;
import de.mhus.vance.shared.prak.ExtractedItem;
import de.mhus.vance.shared.prak.ItemType;
import de.mhus.vance.shared.prak.LongTermMemoryDecision;
import de.mhus.vance.shared.prak.Scope;
import de.mhus.vance.shared.prak.SpanStrength;
import de.mhus.vance.shared.prak.WindowSpan;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SpanStrengthDeriverTest {

    private ChatMessageService chatMessageService;
    private SpanStrengthDeriver deriver;

    @BeforeEach
    void setUp() {
        chatMessageService = mock(ChatMessageService.class);
        deriver = new SpanStrengthDeriver(new HotPathMarkerDetector(), chatMessageService);
    }

    // ─── derive: STRONG ───

    @Test
    void derive_hotPathMarkerMessageBecomesStrong() {
        List<SpanMessage> msgs = List.of(
                user("m1", "Ab jetzt nicht mehr committen"));

        StrengthDerivation d = deriver.derive(msgs, emptyOutput());

        assertThat(d.overrides()).containsExactly(entry("m1", SpanStrength.STRONG));
    }

    @Test
    void derive_highImportanceEvidenceBecomesStrong() {
        List<SpanMessage> msgs = List.of(
                user("m1", "some user content"),
                assistant("m2", "ack"));
        EvaluationOutput out = outputWith(item(
                "evt-1", 4, evidenceOf("m1"), LongTermMemoryDecision.skip("test")));

        StrengthDerivation d = deriver.derive(msgs, out);

        assertThat(d.overrides()).containsEntry("m1", SpanStrength.STRONG);
    }

    @Test
    void derive_promoteEvidenceEvenLowImportanceBecomesStrong() {
        List<SpanMessage> msgs = List.of(user("m1", "content"));
        EvaluationOutput out = outputWith(item(
                "evt-1", 2, evidenceOf("m1"),
                LongTermMemoryDecision.promote("low imp but promote")));

        StrengthDerivation d = deriver.derive(msgs, out);

        assertThat(d.overrides()).containsEntry("m1", SpanStrength.STRONG);
    }

    // ─── derive: NORMAL (no override) ───

    @Test
    void derive_lowImportanceSkipEvidenceStaysNormal() {
        List<SpanMessage> msgs = List.of(user("m1", "content"));
        EvaluationOutput out = outputWith(item(
                "evt-1", 2, evidenceOf("m1"),
                LongTermMemoryDecision.skip("low signal")));

        StrengthDerivation d = deriver.derive(msgs, out);

        assertThat(d.overrides()).doesNotContainKey("m1");
    }

    @Test
    void derive_messagePrecedingEvidenceStaysNormalEvenIfAck() {
        // m1 is an ack — would normally be WEAK — but it directly precedes
        // m2 which is item evidence. Context-anchor rule keeps m1 NORMAL.
        List<SpanMessage> msgs = List.of(
                user("m1", "ok"),
                assistant("m2", "Here is the analysis you asked for"));
        EvaluationOutput out = outputWith(item(
                "evt-1", 3, evidenceOf("m2"),
                LongTermMemoryDecision.promote("ok")));

        StrengthDerivation d = deriver.derive(msgs, out);

        assertThat(d.overrides()).doesNotContainKey("m1");
        // m2 also stays NORMAL because importance 3 < 4 — but it IS promote
        // evidence so it's STRONG. Let's just confirm m1 not in overrides.
    }

    // ─── derive: WEAK ───

    @Test
    void derive_ackWithNoEvidenceBecomesWeak() {
        List<SpanMessage> msgs = List.of(user("m1", "ok"));

        StrengthDerivation d = deriver.derive(msgs, emptyOutput());

        assertThat(d.overrides()).containsExactly(entry("m1", SpanStrength.WEAK));
    }

    @Test
    void derive_assistantSelfNarrationBecomesWeak() {
        List<SpanMessage> msgs = List.of(
                assistant("m1", "Ich werde jetzt foo.java lesen und mal schauen"));

        StrengthDerivation d = deriver.derive(msgs, emptyOutput());

        assertThat(d.overrides()).containsExactly(entry("m1", SpanStrength.WEAK));
    }

    @Test
    void derive_substantiveUserContentStaysNormal() {
        List<SpanMessage> msgs = List.of(
                user("m1", "Mir ist gerade aufgefallen dass die Codebase "
                        + "fast überall JSpecify nutzt aber an drei Stellen "
                        + "noch javax.annotation übrig ist."));

        StrengthDerivation d = deriver.derive(msgs, emptyOutput());

        assertThat(d.overrides()).isEmpty();
    }

    @Test
    void derive_handlesMessagesWithNullId() {
        // A null messageId must not crash and must not appear in the overrides.
        List<SpanMessage> msgs = List.of(
                new SpanMessage(null, ChatRole.USER, "ok"),
                user("m2", "ok"));

        StrengthDerivation d = deriver.derive(msgs, emptyOutput());

        // Only m2 makes it in.
        assertThat(d.overrides().keySet()).containsExactly("m2");
    }

    @Test
    void derive_returnsEmptyForEmptyMessageList() {
        StrengthDerivation d = deriver.derive(List.of(), emptyOutput());

        assertThat(d.overrides()).isEmpty();
    }

    // ─── persist ───

    @Test
    void persist_clearsExistingTagsThenWritesPerStrengthGroups() {
        List<SpanMessage> msgs = List.of(
                user("m1", "ok"),
                user("m2", "ab jetzt anders"),
                user("m3", "substantielle nachricht"));

        StrengthDerivation d = new StrengthDerivation(java.util.Map.of(
                "m1", SpanStrength.WEAK,
                "m2", SpanStrength.STRONG));

        when(chatMessageService.removeTagsWithPrefix(any(), eq("STRENGTH:")))
                .thenReturn(2L);
        when(chatMessageService.tagAll(any(), eq("STRENGTH:weak"))).thenReturn(1L);
        when(chatMessageService.tagAll(any(), eq("STRENGTH:strong"))).thenReturn(1L);

        long modified = deriver.persist(msgs, d);

        assertThat(modified).isEqualTo(4L); // 2 cleared + 1 weak + 1 strong

        ArgumentCaptor<Collection<String>> clearCap = collectionCaptor();
        verify(chatMessageService).removeTagsWithPrefix(clearCap.capture(), eq("STRENGTH:"));
        assertThat(clearCap.getValue()).containsExactlyInAnyOrder("m1", "m2", "m3");

        ArgumentCaptor<Collection<String>> weakCap = collectionCaptor();
        verify(chatMessageService).tagAll(weakCap.capture(), eq("STRENGTH:weak"));
        assertThat(weakCap.getValue()).containsExactly("m1");

        ArgumentCaptor<Collection<String>> strongCap = collectionCaptor();
        verify(chatMessageService).tagAll(strongCap.capture(), eq("STRENGTH:strong"));
        assertThat(strongCap.getValue()).containsExactly("m2");
    }

    @Test
    void persist_clearsTagsEvenWhenDerivationEmpty() {
        // Re-derivation may have decided that previous WEAK/STRONG no longer
        // apply — clearing is still required so old tags don't linger.
        List<SpanMessage> msgs = List.of(user("m1", "foo"), user("m2", "bar"));

        when(chatMessageService.removeTagsWithPrefix(any(), eq("STRENGTH:")))
                .thenReturn(0L);

        long modified = deriver.persist(msgs, StrengthDerivation.empty());

        assertThat(modified).isZero();
        verify(chatMessageService).removeTagsWithPrefix(any(), eq("STRENGTH:"));
        verify(chatMessageService, org.mockito.Mockito.never())
                .tagAll(any(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void persist_emptyMessagesIsNoop() {
        long modified = deriver.persist(List.of(), StrengthDerivation.empty());

        assertThat(modified).isZero();
        verifyNoInteractions(chatMessageService);
    }

    @Test
    void persist_skipsMessagesWithNullId() {
        List<SpanMessage> msgs = List.of(
                new SpanMessage(null, ChatRole.USER, "foo"),
                user("m2", "bar"));

        when(chatMessageService.removeTagsWithPrefix(any(), eq("STRENGTH:")))
                .thenReturn(1L);

        deriver.persist(msgs, StrengthDerivation.empty());

        ArgumentCaptor<Collection<String>> cap = collectionCaptor();
        verify(chatMessageService).removeTagsWithPrefix(cap.capture(), eq("STRENGTH:"));
        assertThat(cap.getValue()).containsExactly("m2");
    }

    // ─── factories ───

    private static SpanMessage user(String id, String content) {
        return new SpanMessage(id, ChatRole.USER, content);
    }

    private static SpanMessage assistant(String id, String content) {
        return new SpanMessage(id, ChatRole.ASSISTANT, content);
    }

    private static EvaluationOutput emptyOutput() {
        return EvaluationOutput.empty(new WindowSpan(null, null, 0));
    }

    private static EvaluationOutput outputWith(ExtractedItem... items) {
        return new EvaluationOutput(
                new WindowSpan("from", "to", items.length),
                List.of(items), List.of());
    }

    private static ExtractedItem item(
            String id, int importance, List<Evidence> evidence,
            LongTermMemoryDecision decision) {
        return new ExtractedItem(
                id, ItemType.FACT, importance, "content " + id,
                Scope.project("p"), 0.9, List.of(), evidence, null,
                Decay.SLOW, decision, List.<AffectsExisting>of());
    }

    private static List<Evidence> evidenceOf(String... turnIds) {
        java.util.ArrayList<Evidence> list = new java.util.ArrayList<>();
        for (String t : turnIds) {
            list.add(new Evidence(t, EvidenceRole.USER, null));
        }
        return list;
    }

    private static java.util.Map.Entry<String, SpanStrength> entry(
            String id, SpanStrength s) {
        return java.util.Map.entry(id, s);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Collection<String>> collectionCaptor() {
        return ArgumentCaptor.forClass((Class<Collection<String>>) (Class<?>) Collection.class);
    }
}
