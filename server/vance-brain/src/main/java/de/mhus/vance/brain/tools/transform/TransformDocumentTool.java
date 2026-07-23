package de.mhus.vance.brain.tools.transform;

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
 * Convert a Vance document into a different format and store the
 * result as a new document. General-purpose dispatch over
 * {@link DocumentTransformer} beans.
 *
 * <p>Calling pattern: {@code fromDocument} (source path/id) +
 * {@code toDocument} (target path) + optional {@code format}. When
 * {@code format} is omitted the tool infers it from the
 * {@code toDocument} extension. When {@code toDocument} is omitted
 * the tool generates a path under {@code reports/}.
 *
 * <p>This is the generic counterpart to the format-specific tools
 * like {@code xlsx_from_records} and {@code report_from_markdown}
 * — both still exist for the inline-data use-cases. Use
 * {@code transform_document} when both source and target are real
 * Vance documents.
 */
@Component
@Slf4j
public class TransformDocumentTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "fromDocument", Map.of(
                            "type", "string",
                            "description", "Path or id of the source "
                                    + "document to convert."),
                    "toDocument", Map.of(
                            "type", "string",
                            "description", "Path for the new "
                                    + "document. The extension is "
                                    + "used to infer `format` when "
                                    + "that isn't passed (e.g. "
                                    + "'reports/sales.xlsx' → "
                                    + "format='xlsx'). When omitted, "
                                    + "an auto-generated path under "
                                    + "'reports/' is used."),
                    "format", Map.of(
                            "type", "string",
                            "description", "Optional explicit target "
                                    + "format key. Currently "
                                    + "supported: 'pdf', 'docx', "
                                    + "'xlsx'. Inferred from "
                                    + "toDocument's extension when "
                                    + "omitted."),
                    "projectId", Map.of(
                            "type", "string",
                            "description", "Optional project name; "
                                    + "defaults to the active project."),
                    "title", Map.of(
                            "type", "string",
                            "description", "Optional title — passed "
                                    + "to the renderer (used as "
                                    + "sheet name / cover-page "
                                    + "title) and as the new "
                                    + "document's title. Falls back "
                                    + "to the source document's "
                                    + "title.")),
            "required", List.of("fromDocument"));

    private final EddieContext eddieContext;
    private final DocumentService documentService;
    private final de.mhus.vance.brain.permission.SecurityContextFactory contextFactory;
    private final DocumentLinkBuilder linkBuilder;
    private final DocumentTransformService transformService;
    private final ThinkProcessService thinkProcessService;
    private final ProgressEmitter progressEmitter;

    public TransformDocumentTool(EddieContext eddieContext,
                                 DocumentService documentService,
                                 DocumentLinkBuilder linkBuilder,
                                 DocumentTransformService transformService,
                                 ThinkProcessService thinkProcessService,
                                 ProgressEmitter progressEmitter,
                                 de.mhus.vance.brain.permission.SecurityContextFactory contextFactory) {
        this.contextFactory = contextFactory;
        this.eddieContext = eddieContext;
        this.documentService = documentService;
        this.linkBuilder = linkBuilder;
        this.transformService = transformService;
        this.thinkProcessService = thinkProcessService;
        this.progressEmitter = progressEmitter;
    }

    @Override
    public String name() {
        return "transform_document";
    }

    @Override
    public String description() {
        return "Convert an existing Vance document into a different "
                + "format and store the result as a new document. "
                + "Calls: fromDocument (path/id) + toDocument (path) "
                + "+ optional format. Format is inferred from "
                + "toDocument's extension when omitted. Supported "
                + "today: kind:records → xlsx; markdown → pdf; "
                + "markdown → docx. Returns the new document plus "
                + "`markdownLink` you embed in your reply.";
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
        String fromDocument = paramString(params, "fromDocument");
        if (fromDocument == null) {
            throw new ToolException("'fromDocument' is required");
        }
        String toDocument = paramString(params, "toDocument");
        String format = paramString(params, "format");
        String title = paramString(params, "title");

        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        String projectName = project.getName();
        ThinkProcessDocument process = loadProcess(ctx);

        DocumentDocument source = resolveSourceDoc(fromDocument, projectName, ctx);
        String effectiveTitle = title != null ? title
                : (source.getTitle() != null ? source.getTitle()
                        : leafName(source.getPath()));

        // Format resolution: explicit > inferred-from-toDocument-ext.
        String effectiveFormat = format != null
                ? format.toLowerCase(Locale.ROOT)
                : transformService.inferFormat(toDocument);
        if (effectiveFormat == null) {
            throw new ToolException(
                    "Cannot determine target format. Pass `format` "
                            + "explicitly or use a `toDocument` path "
                            + "with a recognised extension. Supported: "
                            + transformService.supportedFormats());
        }

        DocumentTransformer transformer = transformService.dispatch(source, effectiveFormat);

        emit(process, StatusTag.INFO,
                "Transforming '" + source.getPath()
                        + "' → " + effectiveFormat + "…");

        long started = System.currentTimeMillis();
        DocumentTransformer.Result rendered = transformer.transform(source, effectiveTitle);
        long elapsedMs = System.currentTimeMillis() - started;

        String finalPath = toDocument != null
                ? toDocument
                : defaultOutputPath(effectiveTitle, transformer.targetExtension());

        DocumentDocument created;
        try (InputStream in = new ByteArrayInputStream(rendered.bytes())) {
            created = documentService.create(
                    ctx.tenantId(),
                    projectName,
                    finalPath,
                    rendered.suggestedTitle() != null
                            ? rendered.suggestedTitle() : effectiveTitle,
                    List.of("transform", effectiveFormat),
                    transformer.targetMimeType(),
                    in,
                    ctx.userId(),
                    contextFactory.writeActor(ctx.tenantId(), ctx.userId(), finalPath));
        } catch (IOException e) {
            throw new ToolException(
                    "Could not store transformed document: "
                            + e.getMessage());
        }

        String vanceUri = DocumentLinkBuilder.buildVanceUri(
                null, created.getPath(), transformer.targetExtension(),
                DocumentLinkBuilder.defaultModeForKind(transformer.targetExtension()));
        String markdownLink = linkBuilder.linkFor(created, projectName);

        log.info("TransformDocumentTool tenant='{}' from='{}' "
                        + "format={} bytes={} elapsedMs={} to='{}'",
                ctx.tenantId(), source.getPath(), effectiveFormat,
                rendered.bytes().length, elapsedMs, finalPath);
        emit(process, StatusTag.INFO,
                String.format(Locale.ROOT,
                        "Done — %d KB %s saved as '%s'.",
                        rendered.bytes().length / 1024,
                        effectiveFormat.toUpperCase(Locale.ROOT),
                        finalPath));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", source.getPath());
        out.put("to", created.getPath());
        out.put("format", effectiveFormat);
        out.put("size", created.getSize());
        out.put("elapsedMs", elapsedMs);
        out.put("vanceUri", vanceUri);
        out.put("markdownLink", markdownLink);
        return out;
    }

    // ── Helpers ───────────────────────────────────────────────────

    private DocumentDocument resolveSourceDoc(String ref,
                                              String projectName,
                                              ToolInvocationContext ctx) {
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

    static String defaultOutputPath(@Nullable String title, String extension) {
        String stamp = DateTimeFormatter
                .ofPattern("yyyy-MM-dd-HHmmss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        String slug = title == null || title.isBlank()
                ? "document"
                : slug(title);
        return "reports/" + slug + "-" + stamp + "." + extension;
    }

    /** Same slug semantics as {@code ReportFromMarkdownTool.slug}
     *  — keep punctuation-safe filenames. */
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
        String out = sb.toString().replaceAll("^-+|-+$", "");
        if (out.isEmpty()) return "document";
        return out.length() > 60 ? out.substring(0, 60) : out;
    }

    private static String leafName(String path) {
        if (path == null) return "document";
        int slash = path.lastIndexOf('/');
        String leaf = slash < 0 ? path : path.substring(slash + 1);
        int dot = leaf.lastIndexOf('.');
        return dot > 0 ? leaf.substring(0, dot) : leaf;
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
