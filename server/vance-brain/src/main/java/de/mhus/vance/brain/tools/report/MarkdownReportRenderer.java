package de.mhus.vance.brain.tools.report;

/**
 * Pluggable renderer for {@code report_from_markdown}. One bean per
 * target format. Spring discovers them as a {@code List<…>} which
 * {@link MarkdownReportService} indexes by {@link #format()}.
 *
 * <p>Renderers are stateless; the per-call state lives in the
 * {@link MarkdownReportContext} argument.
 */
public interface MarkdownReportRenderer {

    /** Lower-case format key, e.g. {@code "pdf"}, {@code "docx"}. */
    String format();

    /** MIME type emitted for this format, e.g. {@code "application/pdf"}. */
    String mimeType();

    /** File extension (no leading dot), e.g. {@code "pdf"}. */
    String fileExtension();

    /** Render the input context into raw bytes. */
    byte[] render(MarkdownReportContext context);
}
