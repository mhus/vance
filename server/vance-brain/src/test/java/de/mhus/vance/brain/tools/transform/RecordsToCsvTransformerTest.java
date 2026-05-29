package de.mhus.vance.brain.tools.transform;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.document.kind.RecordsDocument;
import de.mhus.vance.shared.document.kind.RecordsItem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecordsToCsvTransformerTest {

    private static RecordsItem item(String... pairs) {
        Map<String, String> v = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) v.put(pairs[i], pairs[i + 1]);
        return new RecordsItem(v, new LinkedHashMap<>(), new ArrayList<>());
    }

    // ─── quoteIfNeeded ──────────────────────────────────────────────

    @Test
    void quoteIfNeeded_plainValueStaysBare() {
        assertThat(RecordsToCsvTransformer.quoteIfNeeded("hello"))
                .isEqualTo("hello");
    }

    @Test
    void quoteIfNeeded_emptyStaysEmpty() {
        assertThat(RecordsToCsvTransformer.quoteIfNeeded("")).isEqualTo("");
        assertThat(RecordsToCsvTransformer.quoteIfNeeded(null)).isEqualTo("");
    }

    @Test
    void quoteIfNeeded_commaForcesQuotes() {
        assertThat(RecordsToCsvTransformer.quoteIfNeeded("a, b"))
                .isEqualTo("\"a, b\"");
    }

    @Test
    void quoteIfNeeded_internalQuoteIsDoubled() {
        assertThat(RecordsToCsvTransformer.quoteIfNeeded("she said \"hi\""))
                .isEqualTo("\"she said \"\"hi\"\"\"");
    }

    @Test
    void quoteIfNeeded_newlinesForceQuotes() {
        assertThat(RecordsToCsvTransformer.quoteIfNeeded("line1\nline2"))
                .isEqualTo("\"line1\nline2\"");
    }

    @Test
    void quoteIfNeeded_leadingOrTrailingSpaceForcesQuotes() {
        assertThat(RecordsToCsvTransformer.quoteIfNeeded(" leading"))
                .isEqualTo("\" leading\"");
        assertThat(RecordsToCsvTransformer.quoteIfNeeded("trailing "))
                .isEqualTo("\"trailing \"");
    }

    // ─── render (full CSV body) ─────────────────────────────────────

    @Test
    void render_basicShape() {
        RecordsDocument doc = new RecordsDocument("records",
                List.of("name", "qty"),
                List.of(item("name", "Apple", "qty", "3"),
                        item("name", "Banana", "qty", "5")),
                new LinkedHashMap<>());
        String csv = RecordsToCsvTransformer.render(doc);
        assertThat(csv).isEqualTo("name,qty\r\nApple,3\r\nBanana,5\r\n");
    }

    @Test
    void render_handlesQuotingInsideCells() {
        RecordsDocument doc = new RecordsDocument("records",
                List.of("name", "note"),
                List.of(item("name", "Eve, M.D.",
                              "note", "she said \"hi\"")),
                new LinkedHashMap<>());
        String csv = RecordsToCsvTransformer.render(doc);
        assertThat(csv).isEqualTo(
                "name,note\r\n"
                + "\"Eve, M.D.\",\"she said \"\"hi\"\"\"\r\n");
    }

    @Test
    void render_emptyItemsStillProducesHeader() {
        RecordsDocument doc = new RecordsDocument("records",
                List.of("a", "b"), List.of(), new LinkedHashMap<>());
        String csv = RecordsToCsvTransformer.render(doc);
        assertThat(csv).isEqualTo("a,b\r\n");
    }

    @Test
    void render_missingFieldsLeaveEmptyCells() {
        RecordsDocument doc = new RecordsDocument("records",
                List.of("a", "b", "c"),
                List.of(item("a", "x", "c", "z")),  // b missing
                new LinkedHashMap<>());
        String csv = RecordsToCsvTransformer.render(doc);
        assertThat(csv).isEqualTo("a,b,c\r\nx,,z\r\n");
    }
}
