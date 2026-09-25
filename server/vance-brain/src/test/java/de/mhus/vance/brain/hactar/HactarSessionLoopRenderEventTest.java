package de.mhus.vance.brain.hactar;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link HactarSessionLoop#renderForLlm}: the {@code <process-event>}
 * rendering — the machine-facts block (Live-Fund 8) must surface the
 * engine-verified payload (recipePath, outputPaths) BEFORE the child's
 * human summary, because the summary is the child LLM's own description
 * and can be plain wrong (observed live: child persisted a script, its
 * summary claimed "only the plan exists", the parent believed the
 * summary and re-spawned seven times).
 */
class HactarSessionLoopRenderEventTest {

    private static SteerMessage.ProcessEvent event(
            @org.jspecify.annotations.Nullable String humanSummary,
            @org.jspecify.annotations.Nullable Map<String, Object> payload) {
        return new SteerMessage.ProcessEvent(
                Instant.now(), null, "child-1", ProcessEventType.DONE, humanSummary, payload, "evt-1", null);
    }

    @Test
    void processEvent_rendersMachineFactsBeforeTheHumanSummary() {
        String rendered = HactarSessionLoop.renderForLlm(event(
                "The rewrite plan was created, but the script was not written.",
                Map.of(
                        "eventType", "DONE",
                        "recipePath", "_vance/scripts/_slart/73d84112/find-primes-sieve.js",
                        "outputPaths", List.of("_vance/scripts/_slart/73d84112/find-primes-sieve.js"))));

        assertThat(rendered).startsWith("<process-event type=\"done\">");
        // Machine facts come first and carry the persisted path the
        // parent needs, even when the summary denies it.
        int facts = rendered.indexOf("machine facts:");
        int report = rendered.indexOf("report:");
        assertThat(facts).isNotNegative();
        assertThat(report).isGreaterThan(facts);
        assertThat(rendered)
                .contains("recipePath: _vance/scripts/_slart/73d84112/find-primes-sieve.js")
                .contains("report: The rewrite plan was created")
                .endsWith("</process-event>");
    }

    @Test
    void processEvent_skipsNestedAndEmptyPayloadValues() {
        String rendered = HactarSessionLoop.renderForLlm(event(
                "done",
                Map.of(
                        "eventType",
                        "DONE",
                        "blank",
                        "  ",
                        "nested",
                        Map.of("a", 1),
                        "list",
                        List.of(1, "two"),
                        "numbers",
                        42)));

        assertThat(rendered)
                .contains("eventType: DONE")
                .contains("list: 1, two")
                .contains("numbers: 42")
                .doesNotContain("blank:")
                .doesNotContain("nested:");
    }

    @Test
    void processEvent_withoutPayload_rendersSummaryOnly() {
        String rendered = HactarSessionLoop.renderForLlm(event("child says hi", null));

        assertThat(rendered).isEqualTo("<process-event type=\"done\">report: child says hi\n</process-event>");
    }

    @Test
    void processEvent_escapesSummaryText() {
        String rendered = HactarSessionLoop.renderForLlm(event("a < b & c", null));

        assertThat(rendered).contains("report: a &lt; b &amp; c");
    }
}
