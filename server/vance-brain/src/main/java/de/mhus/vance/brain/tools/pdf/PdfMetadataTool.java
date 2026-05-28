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
import java.util.Calendar;
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
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Read PDF metadata: title, author, subject, keywords, creator,
 * producer, page count, creation + modification timestamps. Useful
 * before a full {@code pdf_read} ("how big is this paper, what's it
 * about") and for building citation entries.
 *
 * <p>All metadata fields are optional in the PDF spec — fields that
 * weren't set by the producing application are simply omitted from
 * the result map (no null-padding).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PdfMetadataTool implements Tool {

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
                            "description", "Alternative: Mongo id. "
                                    + "Use one of path/id.")),
            "required", List.of());

    private final EddieContext eddieContext;
    private final DocumentService documentService;

    @Override
    public String name() {
        return "pdf_metadata";
    }

    @Override
    public String description() {
        return "Read a PDF's metadata: title, author, subject, "
                + "keywords, creator, producer, pageCount, "
                + "creationDate, modificationDate, sizeBytes. Returns "
                + "only fields that were actually populated in the "
                + "PDF — missing fields are omitted (not nulled). "
                + "Cheap probe before pdf_read.";
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
        byte[] bytes = loadBytes(doc);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", doc.getId());
        out.put("path", doc.getPath());
        out.put("sizeBytes", bytes.length);

        try (RandomAccessReadBuffer source = new RandomAccessReadBuffer(bytes);
             PDDocument pdf = Loader.loadPDF(source)) {
            out.put("pageCount", pdf.getNumberOfPages());
            PDDocumentInformation info = pdf.getDocumentInformation();
            if (info != null) {
                putIfPresent(out, "title", info.getTitle());
                putIfPresent(out, "author", info.getAuthor());
                putIfPresent(out, "subject", info.getSubject());
                putIfPresent(out, "keywords", info.getKeywords());
                putIfPresent(out, "creator", info.getCreator());
                putIfPresent(out, "producer", info.getProducer());
                putIfPresent(out, "creationDate", formatDate(info.getCreationDate()));
                putIfPresent(out, "modificationDate", formatDate(info.getModificationDate()));
            }
        } catch (IOException e) {
            throw new ToolException(
                    "PDF metadata read failed: " + e.getMessage());
        }

        log.info("PdfMetadataTool tenant='{}' path='{}' pageCount={}",
                ctx.tenantId(), doc.getPath(), out.get("pageCount"));
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
                            + "(mime='" + mime + "').");
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

    private static void putIfPresent(Map<String, Object> out,
                                     String key, @Nullable String value) {
        if (value != null && !value.isBlank()) out.put(key, value.trim());
    }

    /** ISO-8601 from PDFBox's Calendar (which already carries TZ). */
    static @Nullable String formatDate(@Nullable Calendar cal) {
        if (cal == null) return null;
        return cal.toInstant().toString();
    }

    private static @Nullable String paramString(@Nullable Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
