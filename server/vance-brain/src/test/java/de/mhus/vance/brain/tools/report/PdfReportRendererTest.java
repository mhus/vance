package de.mhus.vance.brain.tools.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/**
 * Smoke tests for {@link PdfReportRenderer}: render a small
 * markdown document and verify the bytes are a valid PDF whose
 * text-layer round-trips the input. No Spring context needed —
 * the renderer is constructible standalone.
 */
class PdfReportRendererTest {

    private static final String SAMPLE_MD = """
            # Hello

            This is a **test** report with `inline code`.

            - item one
            - item two
              - nested

            | A | B |
            |---|---|
            | 1 | 2 |
            | 3 | 4 |

            ```
            code block content
            ```
            """;

    @Test
    void render_producesValidPdfBytes() throws Exception {
        PdfReportRenderer r = new PdfReportRenderer();
        MarkdownReportContext ctx = new MarkdownReportContext(
                SAMPLE_MD, "Test Report", "Tester", "tenant", "project");

        byte[] bytes = r.render(ctx);

        assertThat(bytes).isNotEmpty();
        // PDF magic header
        assertThat(new String(bytes, 0, 5)).isEqualTo("%PDF-");
    }

    @Test
    void render_textLayerContainsExpectedStrings() throws Exception {
        PdfReportRenderer r = new PdfReportRenderer();
        MarkdownReportContext ctx = new MarkdownReportContext(
                SAMPLE_MD, "Test Report", null, "tenant", "project");

        byte[] bytes = r.render(ctx);
        String text;
        try (RandomAccessReadBuffer buf = new RandomAccessReadBuffer(
                     new ByteArrayInputStream(bytes));
             PDDocument doc = Loader.loadPDF(buf)) {
            text = new PDFTextStripper().getText(doc);
        }

        assertThat(text).contains("Test Report");
        assertThat(text).contains("Hello");
        assertThat(text).contains("item one");
        assertThat(text).contains("item two");
        assertThat(text).contains("code block content");
    }

    @Test
    void render_emptyMarkdownStillProducesValidPdf() {
        PdfReportRenderer r = new PdfReportRenderer();
        MarkdownReportContext ctx = new MarkdownReportContext(
                "", null, null, "tenant", "project");

        byte[] bytes = r.render(ctx);
        assertThat(bytes).isNotEmpty();
        assertThat(new String(bytes, 0, 5)).isEqualTo("%PDF-");
    }

    @Test
    void buildHtmlDocument_escapesTitle() {
        String html = PdfReportRenderer.buildHtmlDocument(
                new MarkdownReportContext("body", "Tom & <Jerry>", null, "t", "p"),
                "<p>body</p>");
        assertThat(html).contains("Tom &amp; &lt;Jerry&gt;");
        assertThat(html).contains("<p>body</p>");
    }

    @Test
    void format_andMimeType_areCorrect() {
        PdfReportRenderer r = new PdfReportRenderer();
        assertThat(r.format()).isEqualTo("pdf");
        assertThat(r.mimeType()).isEqualTo("application/pdf");
        assertThat(r.fileExtension()).isEqualTo("pdf");
    }
}
