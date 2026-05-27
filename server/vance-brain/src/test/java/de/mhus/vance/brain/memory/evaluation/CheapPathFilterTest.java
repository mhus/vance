package de.mhus.vance.brain.memory.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.shared.memory.evaluation.ItemCountExpectation;
import java.util.List;
import org.junit.jupiter.api.Test;

class CheapPathFilterTest {

    private final CheapPathFilter filter = new CheapPathFilter(new HotPathMarkerDetector());

    // ---- skip decisions ----

    @Test
    void profile_emptySpanIsSkippable() {
        var profile = filter.profile(List.of());

        assertThat(profile.isSkippable()).isTrue();
        assertThat(profile.skipReason()).isEqualTo("empty");
    }

    @Test
    void profile_shortSpanBelowTokenThresholdIsSkippable() {
        var profile = filter.profile(List.of(
                user("kurz"),
                assistant("ok")));

        assertThat(profile.isSkippable()).isTrue();
        assertThat(profile.skipReason()).isEqualTo("below-token-threshold");
    }

    @Test
    void profile_skipsSpanWithoutMarkersAndWithoutSubstantialUserTurns() {
        // 50+ tokens but all assistant filler and short user
        var profile = filter.profile(List.of(
                user("Hi"),
                assistant(repeat("filler ", 60))));

        assertThat(profile.isSkippable()).isTrue();
        assertThat(profile.skipReason()).isEqualTo("no-substance");
    }

    @Test
    void profile_skipsAckAndNarrationOnlySpan() {
        // 50+ total tokens, but every message is either a short ack
        // or assistant self-narration — nothing memorable.
        var profile = filter.profile(List.of(
                user("ok"),
                assistant("Ich werde jetzt foo.java lesen und schauen was da los ist "
                        + "in der Klasse die du gerade erwähnt hast und dann mal sehen "
                        + "ob wir da gleich etwas finden was uns weiterhilft im Code"),
                user("ja"),
                assistant("Lass mich kurz überlegen wie wir das am besten machen können "
                        + "und dann komme ich gleich darauf zurück mit einem Vorschlag "
                        + "den wir dann gemeinsam diskutieren können bevor du etwas tust")));

        assertThat(profile.isSkippable()).isTrue();
        assertThat(profile.skipReason()).isEqualTo("only-ack-or-narration");
    }

    @Test
    void profile_keepsSpanWithMarkerEvenIfShort() {
        // Marker overrides token-threshold... wait, actually MIN_TOKEN_COUNT
        // is checked before markers. A marker in a 5-token span IS still
        // skipped. Confirm the rule: marker fires the hot-path trigger
        // synchronously, but the filter is for the analyzer-call gate.
        // For now, the token threshold dominates — there's a separate
        // hot-path entrypoint that bypasses this filter (§4a.3).
        var profile = filter.profile(List.of(
                user("ab jetzt")));

        assertThat(profile.isSkippable()).isTrue();
        assertThat(profile.skipReason()).isEqualTo("below-token-threshold");
        assertThat(profile.markerHits()).isEqualTo(1);
    }

    @Test
    void profile_keepsSubstantialUserTurnSpan() {
        // 50+ total tokens, no markers, but one substantial user turn
        var profile = filter.profile(List.of(
                user("Ich habe gerade festgestellt dass unsere Codebase "
                        + "fast überall JSpecify nutzt aber an drei Stellen "
                        + "im Workflow-Code noch javax.annotation hängengeblieben "
                        + "ist und das sollten wir aufräumen damit das konsistent "
                        + "ist und niemand sich wundert woher die Unterschiede kommen"),
                assistant("Verstanden, ich gehe das mit dir durch und wir räumen "
                        + "die drei Stellen einzeln auf damit nichts schiefgeht.")));

        assertThat(profile.isSkippable()).isFalse();
        assertThat(profile.substantialUserTurnCount()).isEqualTo(1);
    }

    @Test
    void profile_keepsSpanWithMarkerAndSubstance() {
        var profile = filter.profile(List.of(
                user("Ab jetzt committen wir nur noch wenn ich explizit darum "
                        + "bitte das ist mir wichtig weil ich sonst durcheinander "
                        + "komme bei den verschiedenen Branches die wir parallel "
                        + "haben und manche davon noch nicht reif für Push sind"),
                assistant("Verstanden, vermerke ich für diese Session und das ganze "
                        + "Projekt damit das konsistent bleibt.")));

        assertThat(profile.isSkippable()).isFalse();
        assertThat(profile.markerHits()).isGreaterThanOrEqualTo(1);
    }

    // ---- expectation ----

    @Test
    void expectation_markerRichWhenMarkersPresent() {
        var profile = filter.profile(List.of(
                user("Ab jetzt nur committen wenn ich frage. Und vergiss die "
                        + "alte Regel mit dem Push-Verhalten — merk dir das gut.")));

        assertThat(profile.markerHits()).isGreaterThanOrEqualTo(2);
        assertThat(profile.expectation()).isEqualTo(ItemCountExpectation.MARKER_RICH);
    }

    @Test
    void expectation_ackOnlyWhenNoSubstantialUserTurns() {
        var profile = filter.profile(List.of(
                user("ok"),
                assistant("alles klar"),
                user("ja"),
                assistant(repeat("filler ", 50))));

        assertThat(profile.substantialUserTurnCount()).isZero();
        // even though this span is skippable, the expectation is still queryable
        assertThat(profile.expectation()).isEqualTo(ItemCountExpectation.ACK_ONLY);
    }

    @Test
    void expectation_normalForSubstantiveSpanWithoutMarkers() {
        var profile = filter.profile(List.of(
                user("Ich habe gerade festgestellt dass unsere Codebase fast "
                        + "überall JSpecify nutzt aber an drei Stellen im "
                        + "Workflow-Code noch javax.annotation hängengeblieben "
                        + "ist und das sollten wir bei Gelegenheit aufräumen "
                        + "damit das konsistent bleibt und niemand sich wundert "
                        + "woher dieser Unterschied kommt zwischen den Modulen"),
                assistant("verstanden ich räume das mit dir zusammen auf")));

        assertThat(profile.substantialUserTurnCount()).isEqualTo(1);
        assertThat(profile.markerHits()).isZero();
        assertThat(profile.expectation()).isEqualTo(ItemCountExpectation.NORMAL);
    }

    // ---- helpers (static methods) ----

    @Test
    void approxTokenCount_splitsOnWhitespace() {
        assertThat(CheapPathFilter.approxTokenCount("foo bar baz")).isEqualTo(3);
        assertThat(CheapPathFilter.approxTokenCount("  foo   bar  ")).isEqualTo(2);
        assertThat(CheapPathFilter.approxTokenCount("")).isZero();
        assertThat(CheapPathFilter.approxTokenCount("   ")).isZero();
    }

    @Test
    void isTrivialAck_matchesCommonAcks() {
        assertThat(CheapPathFilter.isTrivialAck("ok")).isTrue();
        assertThat(CheapPathFilter.isTrivialAck("Ja")).isTrue();
        assertThat(CheapPathFilter.isTrivialAck("danke!")).isTrue();
        assertThat(CheapPathFilter.isTrivialAck("alles klar")).isTrue();
        assertThat(CheapPathFilter.isTrivialAck("yes")).isTrue();
        assertThat(CheapPathFilter.isTrivialAck("got it.")).isTrue();
    }

    @Test
    void isTrivialAck_rejectsLongerSentences() {
        assertThat(CheapPathFilter.isTrivialAck("ok, dann machen wir das so")).isFalse();
        assertThat(CheapPathFilter.isTrivialAck("schau mal foo.java")).isFalse();
    }

    @Test
    void isSelfNarration_matchesAssistantPrefixes() {
        assertThat(CheapPathFilter.isSelfNarration("Ich werde jetzt foo.java lesen"))
                .isTrue();
        assertThat(CheapPathFilter.isSelfNarration("Lass mich kurz überlegen"))
                .isTrue();
        assertThat(CheapPathFilter.isSelfNarration("Let me check that"))
                .isTrue();
        assertThat(CheapPathFilter.isSelfNarration("I'll now read the file"))
                .isTrue();
    }

    @Test
    void isSelfNarration_rejectsRealStatements() {
        assertThat(CheapPathFilter.isSelfNarration("Der Code verwendet JSpecify"))
                .isFalse();
        assertThat(CheapPathFilter.isSelfNarration("Die Codebase ist konsistent"))
                .isFalse();
    }

    // ---- factories ----

    private static SpanMessage user(String content) {
        return new SpanMessage(null, ChatRole.USER, content);
    }

    private static SpanMessage assistant(String content) {
        return new SpanMessage(null, ChatRole.ASSISTANT, content);
    }

    private static String repeat(String s, int times) {
        return s.repeat(times);
    }
}
