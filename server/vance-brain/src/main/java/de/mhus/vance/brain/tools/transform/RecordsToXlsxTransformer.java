package de.mhus.vance.brain.tools.transform;

import de.mhus.vance.brain.tools.report.XlsxFromRecordsTool;
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
 * Converts a {@code kind: records} document into an XLSX file.
 * Reuses the static renderer from
 * {@link XlsxFromRecordsTool#render(RecordsDocument, String)} so
 * inline and transform paths agree on the sheet shape.
 */
@Component
@RequiredArgsConstructor
public class RecordsToXlsxTransformer implements DocumentTransformer {

    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final DocumentService documentService;

    @Override
    public String targetFormat() {
        return "xlsx";
    }

    @Override
    public String targetMimeType() {
        return XLSX_MIME;
    }

    @Override
    public String targetExtension() {
        return "xlsx";
    }

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
                            + "nothing to export.");
        }
        byte[] bytes = XlsxFromRecordsTool.render(records, title);
        return new Result(bytes, title);
    }

    private String loadAsText(DocumentDocument doc) {
        if (doc.getInlineText() != null) return doc.getInlineText();
        try (InputStream in = documentService.loadContent(doc)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException(
                    "Could not read source document content: "
                            + e.getMessage());
        }
    }
}
