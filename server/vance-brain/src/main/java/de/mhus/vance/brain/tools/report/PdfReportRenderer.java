package de.mhus.vance.brain.tools.report;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import de.mhus.vance.toolpack.ToolException;
import java.io.ByteArrayOutputStream;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Component;

/**
 * Renders a markdown report into a PDF via the
 * commonmark-java &rarr; HTML &rarr; openhtmltopdf pipeline. The
 * HTML side is plain {@code <body>}-content; we wrap it in a
 * minimal HTML5 shell with the print-CSS (A4, Times-style serif,
 * dezent code blocks, page numbers via {@code @bottom-right}).
 *
 * <p>openhtmltopdf is intentionally strict about CSS — we keep
 * the rules small and don't try to be a web browser. The goal is a
 * readable academic-style document, not a UI clone.
 */
@Component
@Slf4j
public class PdfReportRenderer implements MarkdownReportRenderer {

    private static final List<Extension> EXTENSIONS = List.of(
            TablesExtension.create());

    private final Parser parser = Parser.builder()
            .extensions(EXTENSIONS)
            .build();
    private final HtmlRenderer htmlRenderer = HtmlRenderer.builder()
            .extensions(EXTENSIONS)
            .escapeHtml(true)
            .build();

    @Override
    public String format() {
        return "pdf";
    }

    @Override
    public String mimeType() {
        return "application/pdf";
    }

    @Override
    public String fileExtension() {
        return "pdf";
    }

    @Override
    public byte[] render(MarkdownReportContext context) {
        Node ast = parser.parse(context.markdown() == null ? "" : context.markdown());
        String bodyHtml = htmlRenderer.render(ast);

        String html = buildHtmlDocument(context, bodyHtml);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, null);
            if (context.title() != null && !context.title().isBlank()) {
                builder.withProducer("Vance Brain — report_from_markdown");
            }
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (Exception e) {
            throw new ToolException(
                    "PDF rendering failed: " + e.getMessage());
        }
    }

    /**
     * Wrap the rendered body in a self-contained HTML document with
     * the print CSS. openhtmltopdf reads {@code @page} for page
     * boxes and {@code @bottom-right} / {@code @top-center} for
     * running marginals. The CSS is intentionally tiny — we stop
     * at the rules that change the look noticeably; pixel-tuning
     * is for the user's local editor when they switch to DOCX.
     */
    static String buildHtmlDocument(MarkdownReportContext context, String body) {
        String safeTitle = htmlEscape(context.title() != null ? context.title() : "Report");
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n");
        html.append("<html><head>\n");
        html.append("<meta charset=\"UTF-8\"/>\n");
        html.append("<title>").append(safeTitle).append("</title>\n");
        html.append("<style>\n");
        html.append(printCss());
        html.append("</style>\n");
        html.append("</head><body>\n");
        if (context.title() != null && !context.title().isBlank()) {
            html.append("<h1 class=\"report-title\">").append(safeTitle).append("</h1>\n");
        }
        html.append(body);
        html.append("</body></html>\n");
        return html.toString();
    }

    /** Print-CSS as a string constant. Self-contained, openhtmltopdf-
     *  compatible subset. */
    static String printCss() {
        return ""
                + "@page { size: A4; margin: 2.5cm 2cm 2.5cm 2cm;\n"
                + "        @bottom-right { content: counter(page) \" / \" counter(pages); font-size: 9pt; color: #666; }\n"
                + "}\n"
                + "body { font-family: 'Times New Roman', Times, serif; font-size: 11pt; line-height: 1.4; color: #111; }\n"
                + "h1, h2, h3, h4 { font-family: 'Helvetica', Arial, sans-serif; line-height: 1.2; color: #000; }\n"
                + "h1.report-title { font-size: 22pt; margin: 0 0 1.6em 0; border-bottom: 1px solid #888; padding-bottom: 0.4em; }\n"
                + "h1 { font-size: 18pt; margin-top: 1.4em; }\n"
                + "h2 { font-size: 14pt; margin-top: 1.2em; }\n"
                + "h3 { font-size: 12pt; margin-top: 1em; }\n"
                + "p  { margin: 0.4em 0 0.8em 0; text-align: justify; hyphens: auto; }\n"
                + "ul, ol { margin: 0.4em 0 0.8em 1.6em; padding: 0; }\n"
                + "li { margin: 0.15em 0; }\n"
                + "code { font-family: 'Courier New', Courier, monospace; font-size: 10pt; background: #f3f3f3; padding: 0 0.2em; }\n"
                + "pre { font-family: 'Courier New', Courier, monospace; font-size: 9.5pt; background: #f6f6f6; border: 1px solid #ddd; padding: 0.6em 0.8em; margin: 0.8em 0; white-space: pre-wrap; word-wrap: break-word; }\n"
                + "pre code { background: transparent; padding: 0; }\n"
                + "blockquote { border-left: 3px solid #ccc; margin: 0.6em 0; padding: 0.1em 0.9em; color: #444; font-style: italic; }\n"
                + "table { border-collapse: collapse; width: 100%; margin: 0.8em 0; font-size: 10pt; }\n"
                + "th, td { border: 1px solid #aaa; padding: 0.3em 0.5em; text-align: left; vertical-align: top; }\n"
                + "th { background: #efefef; font-weight: bold; }\n"
                + "img { max-width: 100%; height: auto; }\n"
                + "hr { border: 0; border-top: 1px solid #bbb; margin: 1em 0; }\n"
                + "a { color: #1a4a8a; text-decoration: none; }\n";
    }

    /** Minimal HTML escape — used only on title/path strings, never
     *  on body text (commonmark-java already escapes the body). */
    static String htmlEscape(String s) {
        if (s == null) return "";
        return s
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
