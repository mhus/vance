package de.mhus.vance.addon.brain.gtd;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Round-trip + field behaviour of {@link GtdActionCodec} (Markdown form). */
class GtdActionCodecTest {

    private static final String MD = "text/markdown";

    @Test
    void markdown_roundTrip_preservesFields() {
        GtdActionDocument a = new GtdActionDocument(
                GtdActionDocument.KIND, "Call the accountant", "today", "2026-07-31",
                List.of("@calls", "@office"), false, "Ask about Q3.\n- [ ] docs",
                new java.util.LinkedHashMap<>());
        GtdActionDocument back = GtdActionCodec.parse(GtdActionCodec.serialize(a, MD), MD);
        assertThat(back.kind()).isEqualTo("action");
        assertThat(back.title()).isEqualTo("Call the accountant");
        assertThat(back.when()).isEqualTo("today");
        assertThat(back.deadline()).isEqualTo("2026-07-31");
        assertThat(back.contexts()).containsExactly("@calls", "@office");
        assertThat(back.done()).isFalse();
        assertThat(back.body()).isEqualTo("Ask about Q3.\n- [ ] docs");
    }

    @Test
    void markdown_doneFlagAndEmptyWhen_roundTrip() {
        GtdActionDocument a = new GtdActionDocument(
                GtdActionDocument.KIND, "Read book", "", null,
                List.of(), true, "", new java.util.LinkedHashMap<>());
        String md = GtdActionCodec.serialize(a, MD);
        assertThat(md).contains("done: true");
        GtdActionDocument back = GtdActionCodec.parse(md, MD);
        assertThat(back.done()).isTrue();
        assertThat(back.when()).isEmpty();
        assertThat(back.contexts()).isEmpty();
    }

    @Test
    void unknownFrontMatter_preservedAsExtra() {
        String md = "---\nkind: action\nwhen: someday\nenergy: low\n---\n\nBody.";
        GtdActionDocument back = GtdActionCodec.parse(md, MD);
        assertThat(back.when()).isEqualTo("someday");
        assertThat(back.extra()).containsEntry("energy", "low");
        assertThat(GtdActionCodec.serialize(back, MD)).contains("energy: low");
    }
}
