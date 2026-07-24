package de.mhus.vance.shared.document.kind;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Round-trip + field-mapping coverage for {@link CardCodec} (kind: card),
 * the 370-LOC codec behind every Kanban board card. Focus: the three mime
 * paths agree, typed fields survive parse→serialize→parse, and the
 * estimate / labels edge cases the codec explicitly handles.
 */
class CardCodecTest {

    private static final String MD = "text/markdown";
    private static final String JSON = "application/json";
    private static final String YAML = "application/yaml";

    private static CardDocument sample() {
        return new CardDocument(
                "card", "Ship it", "high", "alice",
                List.of("backend", "urgent"), "2026-07-30", 3.0, true,
                "Do the thing.\n- [ ] a\n- [x] b\n", new java.util.LinkedHashMap<>());
    }

    @Test
    void markdown_roundTrip_preservesTypedFields() {
        CardDocument doc = CardCodec.parse(CardCodec.serialize(sample(), MD), MD);

        assertThat(doc.title()).isEqualTo("Ship it");
        assertThat(doc.priority()).isEqualTo("high");
        assertThat(doc.assignee()).isEqualTo("alice");
        assertThat(doc.labels()).containsExactly("backend", "urgent");
        assertThat(doc.dueDate()).isEqualTo("2026-07-30");
        assertThat(doc.estimate()).isEqualTo(3.0);
        assertThat(doc.blocked()).isTrue();
        assertThat(doc.body()).contains("Do the thing.");
    }

    @Test
    void json_and_yaml_roundTrip_agreeWithMarkdown() {
        CardDocument viaJson = CardCodec.parse(CardCodec.serialize(sample(), JSON), JSON);
        CardDocument viaYaml = CardCodec.parse(CardCodec.serialize(sample(), YAML), YAML);

        for (CardDocument d : List.of(viaJson, viaYaml)) {
            assertThat(d.title()).isEqualTo("Ship it");
            assertThat(d.labels()).containsExactly("backend", "urgent");
            assertThat(d.estimate()).isEqualTo(3.0);
            assertThat(d.blocked()).isTrue();
        }
    }

    @Test
    void markdown_integerEstimate_emittedWithoutDecimalPoint() {
        String md = CardCodec.serialize(sample(), MD);
        assertThat(md).contains("estimate: 3").doesNotContain("estimate: 3.0");
    }

    @Test
    void markdown_nonNumericEstimate_roundTripsViaExtra() {
        // "2h" cannot coerce to Double; the codec preserves it in `extra`
        // and re-emits it on the estimate line so it does not vanish.
        String body = "---\nkind: card\ntitle: T\nestimate: 2h\n---\n";
        CardDocument doc = CardCodec.parse(body, MD);
        assertThat(doc.estimate()).isNull();

        String again = CardCodec.serialize(doc, MD);
        assertThat(again).contains("estimate: 2h");
        assertThat(CardCodec.parse(again, MD).extra()).containsEntry("estimate", "2h");
    }

    @Test
    void labels_csvParsesToList_andEmptyWhenAbsent() {
        CardDocument withLabels = CardCodec.parse(
                "---\nkind: card\ntitle: T\nlabels: a, b ,c\n---\n", MD);
        assertThat(withLabels.labels()).containsExactly("a", "b", "c");

        CardDocument none = CardCodec.parse("---\nkind: card\ntitle: T\n---\n", MD);
        assertThat(none.labels()).isEmpty();
    }

    @Test
    void unknownFrontMatterKeys_passThroughExtra() {
        CardDocument doc = CardCodec.parse(
                "---\nkind: card\ntitle: T\ncustomField: v\n---\n", MD);
        assertThat(doc.extra()).containsEntry("customField", "v");

        assertThat(CardCodec.serialize(doc, MD)).contains("customField: v");
    }

    @Test
    void blankTitle_omittedFromMarkdownFrontMatter() {
        CardDocument empty = CardDocument.empty();
        assertThat(CardCodec.serialize(empty, MD)).doesNotContain("title:");
    }

    @Test
    void countCheckboxes_countsTotalAndDone() {
        int[] c = CardCodec.countCheckboxes("- [ ] a\n- [x] b\n- [X] c\ntext\n");
        assertThat(c).containsExactly(3, 2); // 3 total, 2 checked
    }

    @Test
    void countCheckboxes_emptyBody_returnsZeros() {
        assertThat(CardCodec.countCheckboxes("")).containsExactly(0, 0);
    }
}
