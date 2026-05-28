package de.mhus.vance.brain.tools.report;

import de.mhus.vance.api.progress.StatusTag;
import de.mhus.vance.brain.progress.ProgressEmitter;
import de.mhus.vance.brain.tools.document.DocumentLinkBuilder;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Render a markdown source into a downloadable report — PDF or
 * DOCX — and import the result as a Vance Document so the agent
 * can embed the {@code markdownLink} in chat.
 *
 * <p>Two ways to provide the source:
 * <ul>
 *   <li>{@code markdown}: the report body inline, as a single
 *       string. Preferred for compact, freshly-generated reports.</li>
 *   <li>{@code documentRef}: id or path of an existing markdown
 *       document. Preferred when the report has been built up
 *       across turns and lives as a real Document.</li>
 * </ul>
 *
 * <p>Default theme: A4 page, Times-style serif, page numbers at
 * the bottom-right. One look in this iteration — templates come
 * later (see {@code planning/web-office-suite.md}).
 */
@Component
@Slf4j
public class ReportFromMarkdownTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "format", Map.of(
                            "type", "string",
                            "description", "Output format. One of: "
                                    + "'pdf' (final, non-editable; "
                                    + "best for final submission) or "
                                    + "'docx' (Word, editable; for "
                                    + "local polishing in Word / Pages "
                                    + "/ LibreOffice)."),
                    "markdown", Map.of(
                            "type", "string",
                            "description", "Inline markdown content. "
                                    + "Provide this OR documentRef."),
                    "documentRef", Map.of(
                            "type", "string",
                            "description", "Path (or Mongo id) of an "
                                    + "existing markdown document to "
                                    + "render. Provide this OR markdown."),
                    "projectId", Map.of(
                            "type", "string",
                            "description", "Optional project name for "
                                    + "documentRef resolution. Defaults "
                                    + "to the active project."),
                    "title", Map.of(
                            "type", "string",
                            "description", "Document title — appears on "
                                    + "the first page and in the file's "
                                    + "metadata. Falls back to a "
                                    + "default."),
                    "outputPath", Map.of(
                            "type", "string",
                            "description", "Optional path for the new "
                                    + "Document. Default: "
                                    + "'reports/<title-slug>-<timestamp>"
                                    + ".<ext>'.")),
            "required", List.of("format"));

    private final MarkdownReportService reportService;
    private final EddieContext eddieContext;
    private final DocumentService documentService;
    private final DocumentLinkBuilder linkBuilder;
    private final ThinkProcessService thinkProcessService;
    private final ProgressEmitter progressEmitter;

    public ReportFromMarkdownTool(MarkdownReportService reportService,
                                  EddieContext eddieContext,
                                  DocumentService documentService,
                                  DocumentLinkBuilder linkBuilder,
                                  ThinkProcessService thinkProcessService,
                                  ProgressEmitter progressEmitter) {
        this.reportService = reportService;
        this.eddieContext = eddieContext;
        this.documentService = documentService;
        this.linkBuilder = linkBuilder;
        this.thinkProcessService = thinkProcessService;
        this.progressEmitter = progressEmitter;
    }

    @Override
    public String name() {
        return "report_from_markdown";
    }

    @Override
    public String description() {
        return "Render a markdown report as PDF or DOCX and import "
                + "the result as a Vance Document. Provide the "
                + "markdown either inline via `markdown` or by "
                + "reference via `documentRef` (path/id of an "
                + "existing markdown doc). Choose `format=pdf` for "
                + "the final submission file, `format=docx` when "
                + "the user needs to polish it locally in Word or "
                + "LibreOffice. Returns the created Document plus "
                + "`markdownLink` you paste back into chat so the "
                + "user can download it.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public Set<String> labels() {
        return Set.of("eddie", "write", "document");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String format = paramString(params, "format");
        if (format == null) {
            throw new ToolException(
                    "'format' is required — one of "
                            + reportService.supportedFormats());
        }
        String markdown = paramString(params, "markdown");
        String documentRef = paramString(params, "documentRef");
        if ((markdown == null) == (documentRef == null)) {
            throw new ToolException(
                    "Provide exactly one of 'markdown' or 'documentRef'");
        }
        String title = paramString(params, "title");
        String outputPath = paramString(params, "outputPath");

        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        String projectName = project.getName();

        ThinkProcessDocument process = loadProcess(ctx);

        // Resolve source markdown
        String source;
        if (markdown != null) {
            source = markdown;
        } else {
            DocumentDocument srcDoc = resolveSourceDoc(documentRef, projectName, ctx);
            source = loadAsText(srcDoc);
            if (title == null && srcDoc.getTitle() != null) {
                title = srcDoc.getTitle();
            }
        }

        emit(process, StatusTag.INFO,
                "Rendering " + format.toUpperCase(Locale.ROOT)
                        + " report ("
                        + (source.length() / 1024) + " KB markdown)…");

        MarkdownReportContext rctx = new MarkdownReportContext(
                source, title, null, ctx.tenantId(), projectName);

        MarkdownReportService.RenderedReport rendered;
        long started = System.currentTimeMillis();
        try {
            rendered = reportService.render(format, rctx);
        } catch (ToolException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolException(
                    "Report rendering failed: " + e.getMessage());
        }
        long elapsedMs = System.currentTimeMillis() - started;

        // Compose output path
        String finalPath = outputPath != null
                ? outputPath
                : defaultOutputPath(title, rendered.fileExtension());

        // Import as Document
        DocumentDocument created;
        try (InputStream in = new ByteArrayInputStream(rendered.bytes())) {
            created = documentService.create(
                    ctx.tenantId(),
                    projectName,
                    finalPath,
                    title,
                    List.of("report", format.toLowerCase(Locale.ROOT)),
                    rendered.mimeType(),
                    in,
                    ctx.userId());
        } catch (IOException e) {
            throw new ToolException(
                    "Could not store rendered report: " + e.getMessage());
        }
        String vanceUri = DocumentLinkBuilder.buildVanceUri(
                null, created.getPath(),
                rendered.fileExtension(),
                DocumentLinkBuilder.defaultModeForKind(rendered.fileExtension()));
        String markdownLink = linkBuilder.linkFor(created, projectName);

        log.info("ReportFromMarkdownTool tenant='{}' format={} "
                        + "bytes={} elapsedMs={} path='{}'",
                ctx.tenantId(), format, rendered.bytes().length,
                elapsedMs, finalPath);
        emit(process, StatusTag.INFO,
                String.format(Locale.ROOT,
                        "Report done — %d KB %s saved as '%s'.",
                        rendered.bytes().length / 1024,
                        format.toUpperCase(Locale.ROOT),
                        finalPath));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("format", format);
        out.put("path", created.getPath());
        out.put("size", created.getSize());
        out.put("elapsedMs", elapsedMs);
        out.put("vanceUri", vanceUri);
        out.put("markdownLink", markdownLink);
        return out;
    }

    private DocumentDocument resolveSourceDoc(String ref,
                                              String projectName,
                                              ToolInvocationContext ctx) {
        // If it looks like a path (contains '/' or '.'), try by path
        // first; otherwise treat as id. Both fall back to the other
        // path on miss for a forgiving UX.
        boolean pathLike = ref.contains("/") || ref.contains(".");
        if (pathLike) {
            Optional<DocumentDocument> byPath = documentService.findByPath(
                    ctx.tenantId(), projectName, ref);
            if (byPath.isPresent()) return byPath.get();
        }
        Optional<DocumentDocument> byId = documentService.findById(ref);
        if (byId.isPresent()) {
            DocumentDocument doc = byId.get();
            if (!ctx.tenantId().equals(doc.getTenantId())) {
                throw new ToolException(
                        "Source document with id '" + ref
                                + "' is not in your tenant");
            }
            return doc;
        }
        if (!pathLike) {
            Optional<DocumentDocument> byPath = documentService.findByPath(
                    ctx.tenantId(), projectName, ref);
            if (byPath.isPresent()) return byPath.get();
        }
        throw new ToolException(
                "Source document '" + ref + "' not found in project '"
                        + projectName + "'");
    }

    private String loadAsText(DocumentDocument doc) {
        if (doc.getInlineText() != null) return doc.getInlineText();
        try (InputStream in = documentService.loadContent(doc)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException(
                    "Could not read source document: " + e.getMessage());
        }
    }

    static String defaultOutputPath(@Nullable String title, String extension) {
        String stamp = DateTimeFormatter
                .ofPattern("yyyy-MM-dd-HHmmss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        String slug = title == null || title.isBlank()
                ? "report"
                : slug(title);
        return "reports/" + slug + "-" + stamp + "." + extension;
    }

    /** Filesystem-safe slug of a title. Keeps letters / digits /
     *  underscore / hyphen; everything else collapses to '-'. */
    static String slug(String title) {
        StringBuilder sb = new StringBuilder();
        boolean lastDash = false;
        for (int i = 0; i < title.length(); i++) {
            char c = title.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                sb.append(c);
                lastDash = false;
            } else if (!lastDash) {
                sb.append('-');
                lastDash = true;
            }
        }
        String out = sb.toString();
        out = out.replaceAll("^-+|-+$", "");
        if (out.isEmpty()) return "report";
        return out.length() > 60 ? out.substring(0, 60) : out;
    }

    private @Nullable ThinkProcessDocument loadProcess(ToolInvocationContext ctx) {
        if (ctx == null || ctx.processId() == null) return null;
        Optional<ThinkProcessDocument> opt = thinkProcessService.findById(ctx.processId());
        return opt.orElse(null);
    }

    private void emit(@Nullable ThinkProcessDocument process,
                      StatusTag tag, String text) {
        if (process == null) return;
        progressEmitter.emitStatus(process, tag, text);
    }

    private static @Nullable String paramString(@Nullable Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
