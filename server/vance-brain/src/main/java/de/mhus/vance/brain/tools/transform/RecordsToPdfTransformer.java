package de.mhus.vance.brain.tools.transform;

import de.mhus.vance.brain.tools.report.MarkdownReportContext;
import de.mhus.vance.brain.tools.report.PdfReportRenderer;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.kind.RecordsCodec;
import de.mhus.vance.shared.document.kind.RecordsDocument;
import de.mhus.vance.toolpack.ToolException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@code kind: records} → PDF. Renders the records table as a
 * Markdown document with a GFM table, then hands it to the
 * existing {@link PdfReportRenderer} so the layout matches every
 * other markdown-derived PDF in Vance.
 */
@Component
@RequiredArgsConstructor
public class RecordsToPdfTransformer implements DocumentTransformer {

    private final DocumentService documentService;
    private final PdfReportRenderer pdfRenderer;

    @Override public String targetFormat()    { return "pdf"; }
    @Override public String targetMimeType()  { return pdfRenderer.mimeType(); }
    @Override public String targetExtension() { return pdfRenderer.fileExtension(); }

    @Override
    public boolean canTransform(DocumentDocument source) {
        return RecordsCodec.supports(source.getMimeType())
                && "records".equalsIgnoreCase(source.getKind());
    }

    @Override
    public Result transform(DocumentDocument source, String title) {
        RecordsDocument records;
        try {
            records = RecordsCodec.parse(loadAsText(source), source.getMimeType());
        } catch (Exception e) {
            throw new ToolException(
                    "Could not parse source records document: "
                            + e.getMessage());
        }
        if (records.schema().isEmpty()) {
            throw new ToolException(
                    "Source records document has no schema — "
                            + "nothing to render.");
        }
        String md = RecordsToMarkdownTable.render(records, title);
        MarkdownReportContext ctx = new MarkdownReportContext(
                md, title, null,
                source.getTenantId(), source.getProjectId());
        byte[] bytes = pdfRenderer.render(ctx);
        return new Result(bytes, title);
    }

    private String loadAsText(DocumentDocument doc) {
        if (documentService.readContent(doc) != null) return documentService.readContent(doc);
        try (InputStream in = documentService.loadContent(doc)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException(
                    "Could not read source document content: "
                            + e.getMessage());
        }
    }
}
