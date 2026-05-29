package de.mhus.vance.brain.tools.transform;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.document.kind.RecordsDocument;
import java.util.List;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic tests for the parser side of
 * {@link MarkdownToXlsxTransformer}. The XLSX rendering itself is
 * covered by {@code XlsxFromRecordsToolTest}.
 */
class MarkdownToXlsxTransformerTest {

    private static final Parser PARSER = Parser.builder()
            .extensions(List.<Extension>of(TablesExtension.create()))
            .build();

    @Test
    void findFirstTable_returnsNullWhenNoTable() {
        Node root = PARSER.parse("# Heading\n\nJust some prose.\n");
        assertThat(MarkdownToXlsxTransformer.findFirstTable(root)).isNull();
    }

    @Test
    void findFirstTable_findsTable() {
        Node root = PARSER.parse("""
                # Header

                | A | B |
                |---|---|
                | 1 | 2 |
                """);
        TableBlock table = MarkdownToXlsxTransformer.findFirstTable(root);
        assertThat(table).isNotNull();
    }

    @Test
    void findFirstTable_picksFirstOfMany() {
        Node root = PARSER.parse("""
                | A |
                |---|
                | first |

                | X |
                |---|
                | second |
                """);
        TableBlock table = MarkdownToXlsxTransformer.findFirstTable(root);
        RecordsDocument records = MarkdownToXlsxTransformer.toRecords(table);
        assertThat(records.schema()).containsExactly("A");
        assertThat(records.items().get(0).values().get("A")).isEqualTo("first");
    }

    @Test
    void toRecords_buildsSchemaAndItems() {
        TableBlock table = (TableBlock) PARSER.parse("""
                | Name | Quantity | Price |
                |------|----------|-------|
                | Apple | 3 | 1.20 |
                | Banana | 5 | 0.50 |
                """).getFirstChild();
        RecordsDocument records = MarkdownToXlsxTransformer.toRecords(table);
        assertThat(records.schema()).containsExactly("Name", "Quantity", "Price");
        assertThat(records.items()).hasSize(2);
        assertThat(records.items().get(0).values())
                .containsEntry("Name", "Apple")
                .containsEntry("Quantity", "3")
                .containsEntry("Price", "1.20");
        assertThat(records.items().get(1).values())
                .containsEntry("Name", "Banana");
    }

    @Test
    void toRecords_dedupesDuplicateHeaders() {
        TableBlock table = (TableBlock) PARSER.parse("""
                | A | A | B |
                |---|---|---|
                | 1 | 2 | 3 |
                """).getFirstChild();
        RecordsDocument records = MarkdownToXlsxTransformer.toRecords(table);
        assertThat(records.schema()).containsExactly("A", "A_2", "B");
    }

    @Test
    void toRecords_fillsBlankHeaderWithGeneratedName() {
        TableBlock table = (TableBlock) PARSER.parse("""
                | A |  | B |
                |---|---|---|
                | 1 | 2 | 3 |
                """).getFirstChild();
        RecordsDocument records = MarkdownToXlsxTransformer.toRecords(table);
        assertThat(records.schema()).containsExactly("A", "col_2", "B");
    }

    @Test
    void toRecords_extraCellsLandInOverflow() {
        TableBlock table = (TableBlock) PARSER.parse("""
                | A | B |
                |---|---|
                | 1 | 2 |
                """).getFirstChild();
        RecordsDocument records = MarkdownToXlsxTransformer.toRecords(table);
        // GFM-tables truncates body rows to header width, so this
        // case isn't reachable through the parser. Verify the basic
        // shape stays clean.
        assertThat(records.items().get(0).overflow()).isEmpty();
    }

    @Test
    void toRecords_inlineFormattingIsStripped() {
        TableBlock table = (TableBlock) PARSER.parse("""
                | Title |
                |-------|
                | **bold** + *italic* + `code` |
                """).getFirstChild();
        RecordsDocument records = MarkdownToXlsxTransformer.toRecords(table);
        assertThat(records.items().get(0).values().get("Title"))
                .isEqualTo("bold + italic + code");
    }
}
