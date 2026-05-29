package de.mhus.vance.brain.tools.transform;

import de.mhus.vance.shared.document.DocumentDocument;

/**
 * Pluggable converter from one Vance document into another. One
 * bean per (source-shape, target-format) pair; Spring discovers
 * them as a {@code List<DocumentTransformer>} which the
 * {@link DocumentTransformService} indexes for dispatch.
 *
 * <p>Implementations are stateless and reuse the existing
 * format-specific renderers (e.g. {@link
 * de.mhus.vance.brain.tools.report.PdfReportRenderer},
 * {@link de.mhus.vance.brain.tools.report.XlsxFromRecordsTool}).
 * No new format logic is introduced here — just dispatch glue.
 */
public interface DocumentTransformer {

    /** Lower-case target-format key, e.g. {@code "xlsx"},
     *  {@code "pdf"}, {@code "docx"}. */
    String targetFormat();

    /** MIME type of the rendered output. */
    String targetMimeType();

    /** File extension (no dot). */
    String targetExtension();

    /**
     * Whether this transformer can handle the given source.
     * Typically checks the source mime / kind to decide. The
     * dispatcher tries transformers in registration order.
     */
    boolean canTransform(DocumentDocument source);

    /**
     * Convert the source document's content into the target
     * format. The result lands in storage via the regular
     * {@code DocumentService.create} path — the transformer
     * itself only produces the bytes + suggested title.
     */
    Result transform(DocumentDocument source, String title);

    /** Rendered output: raw bytes the dispatcher writes into the
     *  new Vance Document. */
    record Result(byte[] bytes, String suggestedTitle) {}
}
