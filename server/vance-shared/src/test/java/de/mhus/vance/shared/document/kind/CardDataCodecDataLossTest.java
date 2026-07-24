package de.mhus.vance.shared.document.kind;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Regression (code-review-2 S6): the data/card codecs used to silently drop
 * values on a parse→serialize cycle — {@code kind: data} discarded non-String
 * YAML keys, and {@code kind: card} discarded a non-numeric estimate. Both are
 * permanent data loss for user content, so pin the preservation here.
 */
class CardDataCodecDataLossTest {

    private static final String MD = "text/markdown";
    private static final String YAML = "application/yaml";

    @Test
    @SuppressWarnings("unchecked")
    void dataCodec_yaml_preservesNonStringKeys() {
        String body = "kind: data\n2026: x\ntrue: y\nfoo: z\n";
        DataDocument doc = DataCodec.parse(body, YAML);

        assertThat(doc.body()).isInstanceOf(Map.class);
        assertThat((Map<String, Object>) doc.body()).containsKeys("2026", "true", "foo");

        // Round-trip: serialize then re-parse keeps all three keys.
        DataDocument again = DataCodec.parse(DataCodec.serialize(doc, YAML), YAML);
        assertThat((Map<String, Object>) again.body()).containsKeys("2026", "true", "foo");
    }

    @Test
    void cardCodec_markdown_preservesNonNumericEstimate() {
        String body = """
                ---
                kind: card
                title: T
                estimate: big
                ---
                body text
                """;
        CardDocument doc = CardCodec.parse(body, MD);
        // Non-numeric estimate can't fill the typed Double field…
        assertThat(doc.estimate()).isNull();

        // …but it must survive serialization rather than vanish.
        String out = CardCodec.serialize(doc, MD);
        assertThat(out).contains("estimate: big");

        CardDocument again = CardCodec.parse(out, MD);
        assertThat(CardCodec.serialize(again, MD)).contains("estimate: big");
    }
}
