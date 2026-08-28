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

    private final ReportThemeResolver themeResolver;

    public PdfReportRenderer(ReportThemeResolver themeResolver) {
        this.themeResolver = themeResolver;
    }

    /**
     * Egress policy for image/resource URIs referenced from the (untrusted)
     * markdown. Embedded {@code data:} URIs pass; {@code http(s)} passes only
     * when {@link de.mhus.vance.shared.net.SsrfGuard} allows the host; any other
     * scheme (notably {@code file:}) is refused. A refused/blocked URI resolves
     * to {@code null} so openhtmltopdf skips that resource instead of failing the
     * whole render.
     */
    private static final com.openhtmltopdf.extend.FSUriResolver SAFE_URI_RESOLVER =
            (baseUri, uri) -> resolveResourceUri(uri);

    /**
     * Egress policy for a resource URI referenced from the markdown. Returns the
     * URI when openhtmltopdf may fetch it, or {@code null} to skip it. Package
     * -private for unit tests.
     */
    static String resolveResourceUri(String uri) {
        if (uri == null) {
            return null;
        }
        String u = uri.strip();
        if (u.regionMatches(true, 0, "data:", 0, 5)) {
            return u; // embedded — safe
        }
        if (u.regionMatches(true, 0, "http://", 0, 7)
                || u.regionMatches(true, 0, "https://", 0, 8)) {
            try {
                de.mhus.vance.shared.net.SsrfGuard.assertAllowed(u);
                return u;
            } catch (de.mhus.vance.shared.net.SsrfGuard.SsrfException e) {
                return null; // internal/private host → skip the image
            }
        }
        return null; // file:, jar:, ftp:, relative, … → never fetched
    }

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
            // Untrusted markdown can carry ![alt](url) → <img src>. Without a
            // resolver openhtmltopdf fetches file:// (local-file disclosure into
            // the PDF) and any http(s) host (SSRF) at run() time. Restrict it:
            // embedded data: URIs pass; http(s) only if SsrfGuard allows the host
            // (blocks loopback/private/metadata, honors the dev escape hatch);
            // everything else (file:, jar:, ftp:, relative) is refused → skipped.
            builder.useUriResolver(SAFE_URI_RESOLVER);
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
     * running marginals. The CSS is assembled by {@link ReportThemeResolver}
     * — default theme, then an optional named theme, then an optional
     * per-document {@code css:} reference; the cascade means later rules
     * win, so a per-document override beats a per-project theme beats
     * the bundled default.
     */
    String buildHtmlDocument(MarkdownReportContext context, String body) {
        String safeTitle = htmlEscape(context.title() != null ? context.title() : "Report");
        String css = themeResolver.resolveStylesheet(
                context.tenantId(),
                context.projectName(),
                context.theme(),
                context.css());
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n");
        html.append("<html><head>\n");
        html.append("<meta charset=\"UTF-8\"/>\n");
        html.append("<title>").append(safeTitle).append("</title>\n");
        html.append("<style>\n");
        html.append(css);
        html.append("</style>\n");
        html.append("</head><body>\n");
        if (context.title() != null && !context.title().isBlank()) {
            html.append("<h1 class=\"report-title\">").append(safeTitle).append("</h1>\n");
        }
        html.append(body);
        html.append("</body></html>\n");
        return html.toString();
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
