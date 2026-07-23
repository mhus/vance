package de.mhus.vance.addon.brain.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Round-trip + field behaviour of {@link JournalEntryCodec} (Markdown form). */
class JournalEntryCodecTest {

    private static final String MD = "text/markdown";

    @Test
    void markdown_roundTrip_preservesFieldsAndBody() {
        JournalEntryDocument entry = new JournalEntryDocument(
                JournalEntryDocument.KIND, "2026-07-24", "Kickoff", "good",
                List.of("work", "ideas"), "Wrote the journal plan.\n\n## Notes\n- ok",
                new java.util.LinkedHashMap<>());

        String md = JournalEntryCodec.serialize(entry, MD);
        JournalEntryDocument back = JournalEntryCodec.parse(md, MD);

        assertThat(back.kind()).isEqualTo("journal-entry");
        assertThat(back.date()).isEqualTo("2026-07-24");
        assertThat(back.title()).isEqualTo("Kickoff");
        assertThat(back.mood()).isEqualTo("good");
        assertThat(back.tags()).containsExactly("work", "ideas");
        assertThat(back.body()).isEqualTo("Wrote the journal plan.\n\n## Notes\n- ok");
    }

    @Test
    void markdown_missingOptionalFields_areNull() {
        JournalEntryDocument entry = new JournalEntryDocument(
                JournalEntryDocument.KIND, "2026-01-01", "", null,
                List.of(), "Just prose.", new java.util.LinkedHashMap<>());
        JournalEntryDocument back = JournalEntryCodec.parse(
                JournalEntryCodec.serialize(entry, MD), MD);
        assertThat(back.mood()).isNull();
        assertThat(back.tags()).isEmpty();
        assertThat(back.title()).isEmpty();
        assertThat(back.body()).isEqualTo("Just prose.");
    }

    @Test
    void markdown_unknownFrontMatter_isPreservedAsExtra() {
        String md = "---\nkind: journal-entry\ndate: 2026-03-03\nweather: sunny\n---\n\nBody.";
        JournalEntryDocument back = JournalEntryCodec.parse(md, MD);
        assertThat(back.extra()).containsEntry("weather", "sunny");
        // and it round-trips back out
        assertThat(JournalEntryCodec.serialize(back, MD)).contains("weather: sunny");
    }

    @Test
    void parse_emptyBody_yieldsEmptyEntry() {
        JournalEntryDocument back = JournalEntryCodec.parse("", MD);
        assertThat(back.kind()).isEqualTo("journal-entry");
        assertThat(back.body()).isEmpty();
    }
}
