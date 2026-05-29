package de.mhus.vance.brain.tools.transform;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.document.kind.RecordsDocument;
import de.mhus.vance.shared.document.kind.RecordsItem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecordsToMarkdownTableTest {

    private static RecordsItem item(String... pairs) {
        Map<String, String> v = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) v.put(pairs[i], pairs[i + 1]);
        return new RecordsItem(v, new LinkedHashMap<>(), new ArrayList<>());
    }

    @Test
    void render_buildsHeaderAlignmentBody() {
        RecordsDocument doc = new RecordsDocument("records",
                List.of("a", "b"),
                List.of(item("a", "1", "b", "2"),
                        item("a", "3", "b", "4")),
                new LinkedHashMap<>());
        String md = RecordsToMarkdownTable.render(doc, "Hello");
        assertThat(md).isEqualTo("""
                # Hello

                | a | b |
                | --- | --- |
                | 1 | 2 |
                | 3 | 4 |
                """);
    }

    @Test
    void render_withoutTitleOmitsHeading() {
        RecordsDocument doc = new RecordsDocument("records",
                List.of("a"),
                List.of(item("a", "x")),
                new LinkedHashMap<>());
        String md = RecordsToMarkdownTable.render(doc, null);
        assertThat(md).doesNotContain("#");
        assertThat(md).startsWith("| a |");
    }

    @Test
    void render_missingFieldsLeaveEmptyCells() {
        RecordsDocument doc = new RecordsDocument("records",
                List.of("a", "b", "c"),
                List.of(item("a", "x", "c", "z")),  // b missing
                new LinkedHashMap<>());
        String md = RecordsToMarkdownTable.render(doc, null);
        assertThat(md).contains("| x |  | z |");
    }

    @Test
    void render_emptyItemsStillProducesHeader() {
        RecordsDocument doc = new RecordsDocument("records",
                List.of("a", "b"), List.of(), new LinkedHashMap<>());
        String md = RecordsToMarkdownTable.render(doc, null);
        assertThat(md).contains("| a | b |");
        assertThat(md).contains("| --- | --- |");
    }

    @Test
    void escape_pipesAreEscaped() {
        assertThat(RecordsToMarkdownTable.escape("a|b")).isEqualTo("a\\|b");
    }

    @Test
    void escape_newlinesCollapseToSpace() {
        assertThat(RecordsToMarkdownTable.escape("line1\nline2"))
                .isEqualTo("line1 line2");
        assertThat(RecordsToMarkdownTable.escape("a\r\nb")).isEqualTo("a b");
    }

    @Test
    void escape_emptyAndNullSafe() {
        assertThat(RecordsToMarkdownTable.escape("")).isEqualTo("");
        assertThat(RecordsToMarkdownTable.escape(null)).isEqualTo("");
    }
}
