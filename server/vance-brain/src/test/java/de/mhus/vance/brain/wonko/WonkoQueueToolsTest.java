package de.mhus.vance.brain.wonko;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The code-side pieces of the check queue: passage search, quote verification, queue bookkeeping. */
class WonkoQueueToolsTest {

    private static final String RETENTION = """
            # Retention rules

            ## § 7 Retention period
            Payment records must be kept for ten years after the end of the
            calendar year.

            ## § 8 Deletion
            Personal data is deleted after the retention period, see § 7.
            """;

    private static final String PASSWORDS = """
            # Access

            Passwords must be rotated every 90 days.
            """;

    @Test
    void paragraphs_carryTheirHeading() {
        assertThat(WonkoPassageSearch.paragraphs(RETENTION))
                .anyMatch(p -> p.startsWith("Retention rules > § 7 Retention period\n") && p.contains("ten years"))
                .anyMatch(p -> p.startsWith("Retention rules > § 8 Deletion\n"));
    }

    @Test
    void search_ranksParagraphsByDistinctTermsAndSkipsUnrelated() {
        Map<String, String> docs = new LinkedHashMap<>();
        docs.put("rules/retention.md", RETENTION);
        docs.put("rules/access.md", PASSWORDS);

        List<WonkoPassageSearch.Candidate> hits =
                WonkoPassageSearch.search(docs, List.of("retention", "payment records", "years"), 5);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).text()).contains("Payment records must be kept");
        assertThat(hits.get(0).id()).isEqualTo("k1");
        assertThat(hits).noneMatch(h -> h.ref().equals("rules/access.md"));
    }

    @Test
    void search_findsByWordStemsAndThePathWhenNoPhraseMatches() {
        Map<String, String> docs = new LinkedHashMap<>();
        docs.put("rules/data-residency.md", """
                # Data residency

                ## § 1 Location
                Customer data must be stored in data centres within the EU.
                """);
        docs.put("rules/canteen.md", "The canteen is open from 11:30 to 14:00.");

        List<WonkoPassageSearch.Candidate> hits =
                WonkoPassageSearch.search(docs, List.of("cloud storage", "data residency"), 5);

        assertThat(hits).singleElement().satisfies(h -> {
            assertThat(h.ref()).isEqualTo("rules/data-residency.md");
            assertThat(h.text()).startsWith("Data residency > § 1 Location");
        });
    }

    @Test
    void quotes_tolerateFormattingButNotOtherWords() {
        String source = "Payment records **must** be kept for ten years after the end of the\ncalendar year.";

        assertThat(WonkoQuotes.standsIn(source, "“payment records must be kept for ten years”"))
                .isTrue();
        assertThat(WonkoQuotes.standsIn(source, "Payment records must be kept ... calendar year"))
                .isTrue();
        assertThat(WonkoQuotes.standsIn(source, "Payment records must be kept for five years"))
                .isFalse();
        assertThat(WonkoQuotes.standsIn(source, "ten years"))
                .as("too short to prove anything")
                .isFalse();
    }

    @Test
    void enqueue_countsTotals_recheckIsNotDuplicated() {
        WonkoState state = new WonkoState();

        WonkoItemHandlers.enqueue(state, WonkoItemType.LOCATE, 10, "c1");
        WonkoItemHandlers.enqueueRecheck(state, "c1");
        WonkoItemHandlers.enqueueRecheck(state, "c1");

        assertThat(state.getQueue()).hasSize(2);
        assertThat(state.getItemCounts().get(WonkoItemType.COMPARE).getTotal()).isEqualTo(1);
        assertThat(state.getQueue().get(1).isRecheck()).isTrue();
    }
}
