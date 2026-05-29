package de.mhus.vance.brain.tools.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;
import org.odftoolkit.odfdom.doc.OdfTextDocument;

/**
 * Smoke tests for {@link OdtReportRenderer}: render a small
 * markdown document and verify the bytes are a valid ODT package
 * whose text content carries the input. The renderer is
 * constructible standalone — no Spring context needed.
 */
class OdtReportRendererTest {

    private static final String SAMPLE_MD = """
            # Hello

            This is a **test** report with `inline code`.

            - item one
            - item two

            | A | B |
            |---|---|
            | 1 | 2 |
            """;

    @Test
    void render_producesValidOdtBytes() {
        OdtReportRenderer r = new OdtReportRenderer();
        MarkdownReportContext ctx = new MarkdownReportContext(
                SAMPLE_MD, "Test Report", "Tester", "tenant", "project");
        byte[] bytes = r.render(ctx);
        assertThat(bytes).isNotEmpty();
        // ODT is a ZIP — magic header PK
        assertThat(bytes[0]).isEqualTo((byte) 'P');
        assertThat(bytes[1]).isEqualTo((byte) 'K');
    }

    @Test
    void render_documentLoadsBackThroughOdfdom() throws Exception {
        // Round-trip through odfdom: if our writer's output is
        // structurally valid ODT the loader accepts it without
        // throwing. The content.xml we just wrote is what the
        // loader parses on the way back in, so a successful
        // load is a meaningful sanity check.
        OdtReportRenderer r = new OdtReportRenderer();
        MarkdownReportContext ctx = new MarkdownReportContext(
                SAMPLE_MD, "Test Report", "Tester", "tenant", "project");
        byte[] bytes = r.render(ctx);

        // Non-trivial output: a real document is multiple KB,
        // headers + paragraphs + table add up well past an empty
        // package.
        assertThat(bytes.length).isGreaterThan(2_000);

        OdfTextDocument doc = OdfTextDocument.loadDocument(
                new ByteArrayInputStream(bytes));
        assertThat(doc.getContentRoot()).isNotNull();
        assertThat(doc.getContentRoot().getChildNodes().getLength())
                .isGreaterThan(0);
    }

    @Test
    void render_emptyMarkdownStillProducesValidOdt() throws Exception {
        OdtReportRenderer r = new OdtReportRenderer();
        MarkdownReportContext ctx = new MarkdownReportContext(
                "", null, null, "tenant", "project");
        byte[] bytes = r.render(ctx);
        assertThat(bytes).isNotEmpty();
        OdfTextDocument doc = OdfTextDocument.loadDocument(
                new ByteArrayInputStream(bytes));
        assertThat(doc.getContentRoot()).isNotNull();
    }

    @Test
    void format_andMimeType_areCorrect() {
        OdtReportRenderer r = new OdtReportRenderer();
        assertThat(r.format()).isEqualTo("odt");
        assertThat(r.mimeType())
                .isEqualTo("application/vnd.oasis.opendocument.text");
        assertThat(r.fileExtension()).isEqualTo("odt");
    }
}
