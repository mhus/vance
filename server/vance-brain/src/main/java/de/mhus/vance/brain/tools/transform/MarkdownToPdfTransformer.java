package de.mhus.vance.brain.tools.transform;

import de.mhus.vance.brain.tools.report.MarkdownReportContext;
import de.mhus.vance.brain.tools.report.PdfReportRenderer;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.ToolException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Markdown document → PDF via the existing {@link PdfReportRenderer}. */
@Component
@RequiredArgsConstructor
public class MarkdownToPdfTransformer implements DocumentTransformer {

    private final DocumentService documentService;
    private final PdfReportRenderer renderer;

    @Override public String targetFormat() { return "pdf"; }
    @Override public String targetMimeType() { return renderer.mimeType(); }
    @Override public String targetExtension() { return renderer.fileExtension(); }

    @Override
    public boolean canTransform(DocumentDocument source) {
        String mime = source.getMimeType();
        if (mime == null) return false;
        String lower = mime.toLowerCase();
        return lower.equals("text/markdown") || lower.equals("text/x-markdown");
    }

    @Override
    public Result transform(DocumentDocument source, String title) {
        String md = loadAsText(source);
        MarkdownReportContext ctx = new MarkdownReportContext(
                md, title, null,
                source.getTenantId(), source.getProjectId());
        byte[] bytes = renderer.render(ctx);
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
