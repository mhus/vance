package de.mhus.vance.brain.tools.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

/**
 * Smoke tests for {@link DocxReportRenderer}: render markdown into
 * a .docx and verify POI can read it back. Validates the visitor
 * path (headings, lists, tables, code blocks).
 */
class DocxReportRendererTest {

    private static final String SAMPLE_MD = """
            # Main Heading

            A paragraph with **bold** and *italic* and `code`.

            - first
            - second

            | Col A | Col B |
            |-------|-------|
            | val 1 | val 2 |

            ```
            block code
            ```
            """;

    @Test
    void render_producesValidDocxBytes() throws Exception {
        DocxReportRenderer r = new DocxReportRenderer();
        MarkdownReportContext ctx = new MarkdownReportContext(
                SAMPLE_MD, "Test Doc", "Tester", "tenant", "project");

        byte[] bytes = r.render(ctx);
        assertThat(bytes).isNotEmpty();
        // DOCX is a ZIP — magic header is PK\x03\x04
        assertThat(bytes[0]).isEqualTo((byte) 'P');
        assertThat(bytes[1]).isEqualTo((byte) 'K');
    }

    @Test
    void render_documentLoadsAndCarriesExpectedContent() throws Exception {
        DocxReportRenderer r = new DocxReportRenderer();
        MarkdownReportContext ctx = new MarkdownReportContext(
                SAMPLE_MD, "Test Doc", "Tester", "tenant", "project");

        byte[] bytes = r.render(ctx);
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder all = new StringBuilder();
            doc.getParagraphs().forEach(p -> all.append(p.getText()).append('\n'));
            doc.getTables().forEach(t ->
                    t.getRows().forEach(row ->
                            row.getTableCells().forEach(cell ->
                                    all.append(cell.getText()).append('\n'))));
            String text = all.toString();
            assertThat(text).contains("Main Heading");
            assertThat(text).contains("first");
            assertThat(text).contains("second");
            assertThat(text).contains("val 1");
            assertThat(text).contains("val 2");
            assertThat(text).contains("block code");
        }
    }

    @Test
    void render_emptyMarkdownStillProducesValidDocx() throws Exception {
        DocxReportRenderer r = new DocxReportRenderer();
        MarkdownReportContext ctx = new MarkdownReportContext(
                "", null, null, "tenant", "project");
        byte[] bytes = r.render(ctx);
        assertThat(bytes).isNotEmpty();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            assertThat(doc.getParagraphs()).isNotNull();
        }
    }

    @Test
    void format_andMimeType_areCorrect() {
        DocxReportRenderer r = new DocxReportRenderer();
        assertThat(r.format()).isEqualTo("docx");
        assertThat(r.mimeType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(r.fileExtension()).isEqualTo("docx");
    }
}
