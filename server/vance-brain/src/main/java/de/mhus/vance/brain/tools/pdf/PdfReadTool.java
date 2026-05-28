package de.mhus.vance.brain.tools.pdf;

import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Extract plain text from a PDF Document already stored in the
 * project. The PDF is loaded through {@link DocumentService} —
 * inline-text payloads aren't supported for PDFs (the mime is
 * always binary), the bytes come from the storage layer.
 *
 * <p>Returned text is truncated past {@link #MAX_BODY_CHARS} so a
 * long paper doesn't blow out the LLM context window; full length
 * is reported in {@code contentLength}. Optional {@code pages}
 * parameter narrows extraction to a range (1-based, inclusive end)
 * — useful for "summarise chapter 3" without paying the whole
 * document's token cost.
 *
 * <p>Pure text extraction, no OCR. Scanned PDFs with no embedded
 * text layer return an empty (or near-empty) result; the caller
 * should ask the user for a text-bearing PDF or wait for the OCR
 * tool that's coming in a later iteration.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PdfReadTool implements Tool {

    /** Truncation budget for the returned text, in characters. */
    static final int MAX_BODY_CHARS = 50_000;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "projectId", Map.of(
                            "type", "string",
                            "description", "Optional project name. "
                                    + "Defaults to the active project."),
                    "path", Map.of(
                            "type", "string",
                            "description", "Document path inside the "
                                    + "project, e.g. 'papers/smith-2024.pdf'."),
                    "id", Map.of(
                            "type", "string",
                            "description", "Alternative: Mongo id of the "
                                    + "PDF document. Use one of path/id."),
                    "pages", Map.of(
                            "type", "string",
                            "description", "Optional 1-based page range, "
                                    + "e.g. '1-5', '3', or '7-'. End "
                                    + "omitted reads to the last page. "
                                    + "Empty / missing extracts the whole "
                                    + "PDF.")),
            "required", List.of());

    private final EddieContext eddieContext;
    private final DocumentService documentService;

    @Override
    public String name() {
        return "pdf_read";
    }

    @Override
    public String description() {
        return "Extract plain text from a PDF document. Identify it "
                + "by path (within the active project) or by id. "
                + "Optional `pages` parameter (1-based range like "
                + "'1-5' or '3-') narrows extraction. Returns title, "
                + "pageCount, requestedPages, content (text). Long "
                + "documents are truncated past " + MAX_BODY_CHARS
                + " characters; contentLength reports full size. "
                + "Pure text extraction — no OCR. For scanned PDFs "
                + "without an embedded text layer the result will be "
                + "empty; ask the user for a text-bearing PDF.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Set<String> labels() {
        return Set.of("eddie", "read-only", "document");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = resolveDocument(params, ctx);
        ensurePdfMime(doc);
        String pagesRaw = paramString(params, "pages");

        byte[] bytes = loadBytes(doc);

        String text;
        int pageCount;
        String pagesUsed;
        try (RandomAccessReadBuffer source = new RandomAccessReadBuffer(bytes);
             PDDocument pdf = Loader.loadPDF(source)) {
            pageCount = pdf.getNumberOfPages();
            PageRange range = parsePages(pagesRaw, pageCount);

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setStartPage(range.start());
            stripper.setEndPage(range.end());
            String raw = stripper.getText(pdf);
            text = raw == null ? "" : raw;
            pagesUsed = range.formatLabel(pageCount);
        } catch (IOException e) {
            throw new ToolException(
                    "PDF text extraction failed: " + e.getMessage());
        }

        int fullLength = text.length();
        boolean truncated = fullLength > MAX_BODY_CHARS;
        String body = truncated ? text.substring(0, MAX_BODY_CHARS) : text;

        log.info("PdfReadTool tenant='{}' path='{}' pages='{}' bytes={}",
                ctx.tenantId(), doc.getPath(), pagesUsed, fullLength);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", doc.getId());
        out.put("path", doc.getPath());
        if (doc.getTitle() != null) out.put("title", doc.getTitle());
        out.put("pageCount", pageCount);
        out.put("requestedPages", pagesUsed);
        out.put("contentLength", fullLength);
        out.put("truncated", truncated);
        out.put("content", body);
        return out;
    }

    private DocumentDocument resolveDocument(Map<String, Object> params,
                                             ToolInvocationContext ctx) {
        String id = paramString(params, "id");
        String path = paramString(params, "path");
        if (id == null && path == null) {
            throw new ToolException("Provide either 'path' or 'id'");
        }
        if (id != null) {
            DocumentDocument doc = documentService.findById(id)
                    .orElseThrow(() -> new ToolException(
                            "Document with id '" + id + "' not found"));
            if (!ctx.tenantId().equals(doc.getTenantId())) {
                throw new ToolException("Document with id '" + id
                        + "' is not in your tenant");
            }
            return doc;
        }
        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        return documentService.findByPath(ctx.tenantId(), project.getName(), path)
                .orElseThrow(() -> new ToolException(
                        "Document '" + path + "' not found in project '"
                                + project.getName() + "'"));
    }

    private static void ensurePdfMime(DocumentDocument doc) {
        String mime = doc.getMimeType();
        if (mime == null || !mime.toLowerCase(Locale.ROOT).contains("pdf")) {
            throw new ToolException(
                    "Document '" + doc.getPath() + "' is not a PDF "
                            + "(mime='" + mime + "'). Use doc_read for "
                            + "text documents.");
        }
    }

    private byte[] loadBytes(DocumentDocument doc) {
        try (InputStream in = documentService.loadContent(doc)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new ToolException(
                    "Could not load PDF bytes from storage: "
                            + e.getMessage());
        }
    }

    /**
     * Parse a 1-based page-range string against the actual PDF page
     * count. Returns {@code [1, pageCount]} when {@code raw} is blank.
     * Throws {@link ToolException} on malformed input or out-of-range.
     */
    static PageRange parsePages(@Nullable String raw, int pageCount) {
        if (pageCount <= 0) return new PageRange(1, 0);
        if (raw == null || raw.isBlank()) {
            return new PageRange(1, pageCount);
        }
        String s = raw.trim();
        int dash = s.indexOf('-');
        int start;
        int end;
        if (dash < 0) {
            start = parseInt(s, "pages");
            end = start;
        } else {
            String left = s.substring(0, dash).trim();
            String right = s.substring(dash + 1).trim();
            start = left.isEmpty() ? 1 : parseInt(left, "pages start");
            end = right.isEmpty() ? pageCount : parseInt(right, "pages end");
        }
        if (start < 1) {
            throw new ToolException(
                    "Invalid pages range '" + raw + "': start must be >= 1");
        }
        if (end < start) {
            throw new ToolException(
                    "Invalid pages range '" + raw
                            + "': end (" + end + ") < start (" + start + ")");
        }
        if (start > pageCount) {
            throw new ToolException(
                    "Pages range '" + raw + "' is beyond the PDF "
                            + "(it has " + pageCount + " pages)");
        }
        // Clamp end to actual page count silently — common case
        // ("1-100" on a 30-page paper) should not error.
        int clampedEnd = Math.min(end, pageCount);
        return new PageRange(start, clampedEnd);
    }

    private static int parseInt(String s, String fieldHint) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new ToolException(
                    "Invalid " + fieldHint + ": '" + s + "' is not an integer");
        }
    }

    private static @Nullable String paramString(@Nullable Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    /** Inclusive 1-based page range. */
    public record PageRange(int start, int end) {
        public String formatLabel(int pageCount) {
            if (start == 1 && end >= pageCount) return "all";
            if (start == end) return String.valueOf(start);
            return start + "-" + end;
        }
    }
}
