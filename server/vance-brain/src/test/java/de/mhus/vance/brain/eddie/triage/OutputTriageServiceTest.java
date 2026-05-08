package de.mhus.vance.brain.eddie.triage;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.inbox.Criticality;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic tests for the heuristic stage of {@link OutputTriageService}.
 * The LLM stage isn't wired yet — these tests pin the deterministic
 * branch the planning doc estimates handles 70-80% of frames.
 */
class OutputTriageServiceTest {

    private final OutputTriageService service = new OutputTriageService();

    @Test
    void voiceMode_shortProse_passesVerbatim() {
        TriageResult r = service.classify(input("Tests sind grün.", true));

        assertThat(r.decision()).isEqualTo(TriageDecision.VERBATIM);
        assertThat(r.criticality()).isEqualTo(Criticality.LOW);
    }

    @Test
    void voiceMode_longText_goesToInbox() {
        String text = "x".repeat(OutputTriageService.VOICE_INBOX_MIN + 1);

        TriageResult r = service.classify(input(text, true));

        assertThat(r.decision()).isEqualTo(TriageDecision.INBOX);
        assertThat(r.spokenAnnouncement()).isNotNull();
    }

    @Test
    void voiceMode_codeBlock_neverVerbatim_evenWhenShort() {
        // 50 chars total — under voice VERBATIM threshold — but the
        // fenced code block makes verbatim TTS unworkable.
        TriageResult r = service.classify(input(
                "```\nint x = 1;\n```", true));

        assertThat(r.decision()).isEqualTo(TriageDecision.INBOX);
    }

    @Test
    void voiceMode_midLength_goesToReformulate() {
        // Above VERBATIM, below INBOX, no structural markers.
        String text = "Lorem ipsum dolor sit amet ".repeat(8);

        TriageResult r = service.classify(input(text, true));

        assertThat(r.decision()).isEqualTo(TriageDecision.REFORMULATE);
    }

    @Test
    void textMode_codeBlock_underInboxThreshold_staysVerbatim() {
        // In text-mode the user sees the original Markdown, so a small
        // code block can pass through; only really-long output gets
        // pushed to inbox.
        TriageResult r = service.classify(input(
                "```\nint x = 1;\n```", false));

        assertThat(r.decision()).isEqualTo(TriageDecision.VERBATIM);
    }

    @Test
    void textMode_longProse_goesToInbox() {
        String text = "x".repeat(OutputTriageService.TEXT_INBOX_MIN + 1);

        TriageResult r = service.classify(input(text, false));

        assertThat(r.decision()).isEqualTo(TriageDecision.INBOX);
    }

    @Test
    void outputHint_VERBATIM_skipsHeuristic_evenWhenStructural() {
        TriageInput in = new TriageInput(
                "```\nlong code block\n```",
                "VERBATIM",
                "arthur",
                /*voiceMode=*/ true);

        TriageResult r = service.classify(in);

        assertThat(r.decision()).isEqualTo(TriageDecision.VERBATIM);
    }

    @Test
    void outputHint_INBOX_skipsHeuristic_evenWhenShort() {
        TriageInput in = new TriageInput(
                "ok", "INBOX", "ford", false);

        TriageResult r = service.classify(in);

        assertThat(r.decision()).isEqualTo(TriageDecision.INBOX);
        assertThat(r.spokenAnnouncement()).contains("ford");
    }

    @Test
    void outputHint_FREE_fallsThroughToHeuristic() {
        TriageInput in = new TriageInput(
                "Tests sind grün.", "FREE", "ford", true);

        TriageResult r = service.classify(in);

        // Same outcome as without a hint.
        assertThat(r.decision()).isEqualTo(TriageDecision.VERBATIM);
    }

    @Test
    void hardOverride_CRITICAL_REFORMULATE_isClampedToINBOX() {
        TriageResult bad = new TriageResult(
                TriageDecision.REFORMULATE, Criticality.CRITICAL,
                null, "the plan");
        TriageInput input = input("anything", true);

        TriageResult clamped = service.applyHardOverrides(bad, input);

        assertThat(clamped.decision()).isEqualTo(TriageDecision.INBOX);
        assertThat(clamped.criticality()).isEqualTo(Criticality.CRITICAL);
        assertThat(clamped.spokenAnnouncement()).isNotNull();
    }

    @Test
    void hardOverride_passesThrough_whenNotCriticalOrNotReformulate() {
        TriageInput input = input("foo", false);
        TriageResult verbatim = new TriageResult(
                TriageDecision.VERBATIM, Criticality.CRITICAL, null, null);
        TriageResult criticalInbox = new TriageResult(
                TriageDecision.INBOX, Criticality.CRITICAL, "spoken", "summary");
        TriageResult lowReformulate = new TriageResult(
                TriageDecision.REFORMULATE, Criticality.LOW, null, "summary");

        assertThat(service.applyHardOverrides(verbatim, input)).isSameAs(verbatim);
        assertThat(service.applyHardOverrides(criticalInbox, input)).isSameAs(criticalInbox);
        assertThat(service.applyHardOverrides(lowReformulate, input)).isSameAs(lowReformulate);
    }

    @Test
    void looksStructural_recognisesCommonPatterns() {
        assertThat(OutputTriageService.looksStructural("```\ncode\n```")).isTrue();
        assertThat(OutputTriageService.looksStructural("# Header\nbody")).isTrue();
        assertThat(OutputTriageService.looksStructural("- item\n- another")).isTrue();
        assertThat(OutputTriageService.looksStructural("@@ -1 +1 @@\n-old\n+new")).isTrue();
        assertThat(OutputTriageService.looksStructural("{ \"k\": 1 }")).isTrue();
        assertThat(OutputTriageService.looksStructural("just regular prose")).isFalse();
        assertThat(OutputTriageService.looksStructural("")).isFalse();
    }

    @Test
    void summary_truncatesAndPicksFirstLine() {
        String text = "Plan vorgelegt: drei Schritte für Auth-Refactoring.\nDetails folgen.";
        TriageResult r = service.classify(input(text, true));

        // Mid-length triggers REFORMULATE; we just want a summary.
        assertThat(r.memorySummary()).startsWith("Plan vorgelegt");
        assertThat(r.memorySummary()).doesNotContain("Details folgen");
    }

    private static TriageInput input(String text, boolean voiceMode) {
        return new TriageInput(text, null, "arthur", voiceMode);
    }
}
