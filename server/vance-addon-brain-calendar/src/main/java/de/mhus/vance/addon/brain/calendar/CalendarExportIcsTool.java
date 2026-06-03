package de.mhus.vance.addon.brain.calendar;

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
 * Export a Vance {@code kind: calendar} document as an iCalendar
 * (.ics, RFC 5545) file the user can import into Google Calendar,
 * Apple Calendar, Outlook, or any other CalDAV-aware client.
 *
 * <p>One Vance Calendar → one VCALENDAR with one VEVENT per event,
 * including recurrence rules (RRULE), attendees and tags. The
 * generated file is stored as a fresh Vance Document under
 * {@code exports/<calendar-slug>-<timestamp>.ics}; the
 * {@code markdownLink} can be pasted back into chat for a one-click
 * download.
 *
 * <p>This is a one-shot operation, not a continuous sync. After the
 * user imports the file once, re-running the tool produces a new
 * file that the calendar app will treat as duplicate UIDs and merge
 * — that may or may not be desirable. For continuous sync use a
 * subscription URL (planned v2).
 */
@Component
@Slf4j
public class CalendarExportIcsTool implements Tool {

    private static final String ICS_MIME = "text/calendar";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("documentRef", Map.of(
                        "type", "string",
                        "description", "Path or id of a kind:calendar "
                                + "document to export. Required."));
                put("calendarName", Map.of(
                        "type", "string",
                        "description", "Optional display name embedded "
                                + "as X-WR-CALNAME. Defaults to the "
                                + "source document's title."));
                put("outputPath", Map.of(
                        "type", "string",
                        "description", "Path for the generated .ics "
                                + "document. Default: "
                                + "'exports/<calendar-slug>-<timestamp>.ics'."));
                put("projectId", Map.of(
                        "type", "string",
                        "description", "Optional project name; "
                                + "defaults to the active project."));
            }},
            "required", List.of("documentRef"));

    private final EddieContext eddieContext;
    private final DocumentService documentService;
    private final DocumentLinkBuilder linkBuilder;
    private final ThinkProcessService thinkProcessService;
    private final ProgressEmitter progressEmitter;
    private final IcsExportService exportService;

    public CalendarExportIcsTool(EddieContext eddieContext,
                                 DocumentService documentService,
                                 DocumentLinkBuilder linkBuilder,
                                 ThinkProcessService thinkProcessService,
                                 ProgressEmitter progressEmitter,
                                 IcsExportService exportService) {
        this.eddieContext = eddieContext;
        this.documentService = documentService;
        this.linkBuilder = linkBuilder;
        this.thinkProcessService = thinkProcessService;
        this.progressEmitter = progressEmitter;
        this.exportService = exportService;
    }

    @Override public String name() { return "calendar_export_ics"; }

    @Override
    public String description() {
        return "Export a kind:calendar document as a downloadable "
                + "iCalendar (.ics) file the user can import into "
                + "Google Calendar / Apple Calendar / Outlook. One "
                + "Vance event → one VEVENT, with recurrence rules "
                + "preserved. Returns the created Document plus a "
                + "`markdownLink` to paste into chat. One-shot — for "
                + "continuous sync use a subscription URL (planned).";
    }

    @Override public boolean primary() { return false; }

    @Override
    public Set<String> labels() {
        return Set.of("eddie", "write", "document", "calendar");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String documentRef = paramString(params, "documentRef");
        if (documentRef == null) {
            throw new ToolException("documentRef is required");
        }
        String calendarName = paramString(params, "calendarName");
        String outputPath = paramString(params, "outputPath");

        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        String projectName = project.getName();
        ThinkProcessDocument process = loadProcess(ctx);

        DocumentDocument source = resolveSourceDoc(documentRef, projectName, ctx);
        if (!"calendar".equalsIgnoreCase(source.getKind())) {
            throw new ToolException(
                    "Source document '" + source.getPath()
                            + "' has kind '" + source.getKind()
                            + "', expected kind:calendar.");
        }

        CalendarDocument cal = parseCalendar(source);
        if (cal.events().isEmpty()) {
            throw new ToolException(
                    "Calendar '" + source.getPath() + "' has no "
                            + "events to export.");
        }

        String resolvedName = calendarName != null
                ? calendarName
                : (source.getTitle() != null ? source.getTitle()
                                              : leafName(source.getPath()));

        emit(process, StatusTag.INFO,
                "Rendering .ics for " + cal.events().size() + " events…");

        long started = System.currentTimeMillis();
        byte[] bytes = exportService.toBytes(cal, resolvedName);
        long elapsedMs = System.currentTimeMillis() - started;

        String finalPath = outputPath != null
                ? outputPath
                : defaultOutputPath(resolvedName);

        DocumentDocument created;
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            created = documentService.create(
                    ctx.tenantId(),
                    projectName,
                    finalPath,
                    resolvedName,
                    List.of("calendar", "ics", "export"),
                    ICS_MIME,
                    in,
                    ctx.userId());
        } catch (IOException e) {
            throw new ToolException(
                    "Could not store generated .ics: " + e.getMessage());
        }

        String vanceUri = DocumentLinkBuilder.buildVanceUri(
                null, created.getPath(), "ics",
                DocumentLinkBuilder.defaultModeForKind("ics"));
        String markdownLink = linkBuilder.linkFor(created, projectName);

        log.info("CalendarExportIcsTool tenant='{}' source='{}' "
                        + "events={} elapsedMs={} path='{}'",
                ctx.tenantId(), source.getPath(),
                cal.events().size(), elapsedMs, finalPath);
        emit(process, StatusTag.INFO,
                String.format(Locale.ROOT,
                        ".ics done — %d KB saved as '%s'.",
                        bytes.length / 1024, finalPath));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", created.getPath());
        out.put("eventCount", cal.events().size());
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

    private CalendarDocument parseCalendar(DocumentDocument doc) {
        String body = loadAsText(doc);
        String mime = doc.getMimeType();
        if (!CalendarCodec.supports(mime)) {
            throw new ToolException(
                    "Source document '" + doc.getPath()
                            + "' has mime '" + mime
                            + "' which the calendar codec doesn't "
                            + "support. Use a json / yaml calendar "
                            + "document.");
        }
        try {
            return CalendarCodec.parse(body, mime);
        } catch (Exception e) {
            throw new ToolException(
                    "Could not parse calendar document: " + e.getMessage());
        }
    }

    private String loadAsText(DocumentDocument doc) {
        if (doc.getInlineText() != null) return doc.getInlineText();
        try (InputStream in = documentService.loadContent(doc)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException(
                    "Could not read source document content: " + e.getMessage());
        }
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

    static String defaultOutputPath(@Nullable String title) {
        String stamp = DateTimeFormatter
                .ofPattern("yyyy-MM-dd-HHmmss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        String slug = (title == null || title.isBlank())
                ? "calendar" : IcsToCalendarTool.slug(title);
        return "exports/" + slug + "-" + stamp + ".ics";
    }

    private static String leafName(String path) {
        if (path == null) return "calendar";
        int slash = path.lastIndexOf('/');
        String leaf = slash < 0 ? path : path.substring(slash + 1);
        int dot = leaf.lastIndexOf('.');
        return dot > 0 ? leaf.substring(0, dot) : leaf;
    }

    private static @Nullable String paramString(@Nullable Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
