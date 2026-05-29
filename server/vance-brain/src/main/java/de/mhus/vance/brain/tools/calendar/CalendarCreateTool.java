package de.mhus.vance.brain.tools.calendar;

import de.mhus.vance.api.progress.StatusTag;
import de.mhus.vance.brain.progress.ProgressEmitter;
import de.mhus.vance.brain.tools.document.DocumentLinkBuilder;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.kind.CalendarCodec;
import de.mhus.vance.shared.document.kind.CalendarDocument;
import de.mhus.vance.shared.document.kind.CalendarEvent;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Create (or overwrite) a {@code kind: calendar} document from a
 * typed list of events.
 *
 * <p>Direct calendar-creation tool — preferred over the generic
 * {@code doc_create_kind(kind="calendar", body=…)} path because the
 * tool inventory then advertises the calendar capability by name.
 * Without this tool the LLM tends to hallucinate Google-Calendar-API
 * calls instead of finding the {@code kind: calendar} document type.
 *
 * <p>One call produces one Calendar document with all the events.
 * The renderer is the regular Calendar view (month + agenda); the
 * Source tab still works for manual edits afterwards.
 */
@Component
@Slf4j
public class CalendarCreateTool implements Tool {

    private static final String YAML_MIME = "application/yaml";

    private static final Map<String, Object> EVENT_PROPS;
    static {
        EVENT_PROPS = new LinkedHashMap<>();
        EVENT_PROPS.put("id", Map.of(
                "type", "string",
                "description", "Stable identifier (UUID). Optional — "
                        + "a fresh UUID is generated when missing."));
        EVENT_PROPS.put("title", Map.of(
                "type", "string",
                "description", "Display title. Required."));
        EVENT_PROPS.put("start", Map.of(
                "type", "string",
                "description", "ISO-8601 date or date-time. Examples: "
                        + "'2026-06-12T09:00' (local time), "
                        + "'2026-06-12T09:00+02:00' (with offset), "
                        + "'2026-06-12' (all-day). Required."));
        EVENT_PROPS.put("end", Map.of(
                "type", "string",
                "description", "ISO-8601 end. Same format as 'start'. "
                        + "Omit for zero-duration point events."));
        EVENT_PROPS.put("allDay", Map.of(
                "type", "boolean",
                "description", "True for full-day events. start/end "
                        + "should then be date-only strings."));
        EVENT_PROPS.put("location", Map.of(
                "type", "string",
                "description", "Free-form (room, address, Zoom link)."));
        EVENT_PROPS.put("attendees", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "Names / emails / handles."));
        EVENT_PROPS.put("recurrence", Map.of(
                "type", "string",
                "description", "RFC 5545 RRULE, e.g. "
                        + "'FREQ=WEEKLY;BYDAY=MO,WE;UNTIL=20261231T000000Z'. "
                        + "Renderer supports FREQ (DAILY/WEEKLY/"
                        + "MONTHLY/YEARLY), INTERVAL, BYDAY (WEEKLY "
                        + "only), UNTIL, COUNT. Provide UNTIL or "
                        + "COUNT — otherwise the view caps at 500 "
                        + "occurrences."));
        EVENT_PROPS.put("color", Map.of(
                "type", "string",
                "description", "Palette name (blue/green/red/orange/"
                        + "yellow/purple/pink/teal/gray) or CSS color."));
        EVENT_PROPS.put("tags", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "Free-form filtering tags."));
        EVENT_PROPS.put("notes", Map.of(
                "type", "string",
                "description", "Multi-line description."));
    }

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("events", Map.of(
                        "type", "array",
                        "items", Map.of(
                                "type", "object",
                                "properties", EVENT_PROPS,
                                "required", List.of("title", "start")),
                        "description", "Event objects to put into the "
                                + "calendar. At least one. Order is "
                                + "preserved round-trip but not "
                                + "semantically meaningful."));
                put("title", Map.of(
                        "type", "string",
                        "description", "Document title. Default: "
                                + "'Calendar' (or derived from the "
                                + "first event for unnamed calendars)."));
                put("outputPath", Map.of(
                        "type", "string",
                        "description", "Storage path. Default: "
                                + "'calendars/<title-slug>-<timestamp>"
                                + ".yaml'."));
                put("projectId", Map.of(
                        "type", "string",
                        "description", "Optional project name; "
                                + "defaults to the active project."));
                put("overwrite", Map.of(
                        "type", "boolean",
                        "description", "When true and outputPath "
                                + "exists, replace the body instead "
                                + "of failing. Default false."));
            }},
            "required", List.of("events"));

    private final EddieContext eddieContext;
    private final DocumentService documentService;
    private final DocumentLinkBuilder linkBuilder;
    private final ThinkProcessService thinkProcessService;
    private final ProgressEmitter progressEmitter;

    public CalendarCreateTool(EddieContext eddieContext,
                              DocumentService documentService,
                              DocumentLinkBuilder linkBuilder,
                              ThinkProcessService thinkProcessService,
                              ProgressEmitter progressEmitter) {
        this.eddieContext = eddieContext;
        this.documentService = documentService;
        this.linkBuilder = linkBuilder;
        this.thinkProcessService = thinkProcessService;
        this.progressEmitter = progressEmitter;
    }

    @Override public String name() { return "calendar_create"; }

    @Override
    public String description() {
        return "Create a Vance kind:calendar document from a typed "
                + "list of events. Use this whenever the user asks "
                + "to put appointments / meetings / deadlines into "
                + "a calendar — single events, recurring events "
                + "(RRULE), multi-day events all in one call. "
                + "Renders in the web UI with a month grid + agenda "
                + "view. Returns the created Document plus a "
                + "`markdownLink` to paste back into chat. NOT a "
                + "Google Calendar / iCloud bridge — Vance is an "
                + "internal note-style calendar without push or "
                + "invites.";
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
        List<Map<String, Object>> rawEvents = paramMapList(params, "events");
        if (rawEvents == null || rawEvents.isEmpty()) {
            throw new ToolException(
                    "Provide at least one event in the 'events' "
                            + "array. Each event needs 'title' and "
                            + "'start' (ISO-8601 date or date-time).");
        }

        String title = paramString(params, "title");
        String outputPath = paramString(params, "outputPath");
        boolean overwrite = paramBoolean(params, "overwrite");

        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        String projectName = project.getName();
        ThinkProcessDocument process = loadProcess(ctx);

        List<CalendarEvent> events = new ArrayList<>(rawEvents.size());
        int rowIndex = 0;
        for (Map<String, Object> raw : rawEvents) {
            rowIndex++;
            CalendarEvent ev = buildEvent(raw, rowIndex);
            events.add(ev);
        }

        String effectiveTitle = title != null ? title : deriveTitle(events);
        String finalPath = outputPath != null
                ? outputPath : defaultOutputPath(effectiveTitle);

        CalendarDocument cal = new CalendarDocument(
                "calendar", events, new LinkedHashMap<>());
        String yaml = CalendarCodec.serialize(cal, YAML_MIME);
        byte[] bytes = yaml.getBytes(StandardCharsets.UTF_8);

        emit(process, StatusTag.INFO,
                String.format(Locale.ROOT,
                        "Writing calendar with %d events to '%s'…",
                        events.size(), finalPath));

        DocumentDocument stored;
        Optional<DocumentDocument> existing = documentService.findByPath(
                ctx.tenantId(), projectName, finalPath);
        if (existing.isPresent()) {
            if (!overwrite) {
                throw new ToolException(
                        "A document already exists at '" + finalPath
                                + "'. Pass overwrite=true to replace "
                                + "it or pick a different outputPath.");
            }
            stored = documentService.update(
                    existing.get().getId(),
                    effectiveTitle,
                    null,
                    yaml,
                    null,
                    null,
                    null,
                    null,
                    YAML_MIME);
        } else {
            try (InputStream in = new ByteArrayInputStream(bytes)) {
                stored = documentService.create(
                        ctx.tenantId(),
                        projectName,
                        finalPath,
                        effectiveTitle,
                        List.of("calendar"),
                        YAML_MIME,
                        in,
                        ctx.userId());
            } catch (IOException e) {
                throw new ToolException(
                        "Could not store calendar: " + e.getMessage());
            }
        }

        String vanceUri = DocumentLinkBuilder.buildVanceUri(
                null, stored.getPath(), "calendar",
                DocumentLinkBuilder.defaultModeForKind("calendar"));
        String markdownLink = linkBuilder.linkFor(stored, projectName);

        log.info("CalendarCreateTool tenant='{}' events={} path='{}' overwrite={}",
                ctx.tenantId(), events.size(), finalPath, existing.isPresent());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", stored.getPath());
        out.put("eventCount", events.size());
        out.put("size", stored.getSize());
        out.put("vanceUri", vanceUri);
        out.put("markdownLink", markdownLink);
        if (existing.isPresent()) out.put("replaced", true);
        out.put("addLinks", buildAddLinks(events));
        return out;
    }

    /**
     * One-click "add to my real calendar" deep-links per event.
     * Returned in the same order as the input {@code events} list so
     * the LLM can pair them with its own per-event commentary in
     * chat.
     *
     * <p>The LLM is expected to embed these inline — typically as
     * a Markdown bullet per event with two link-text labels:
     * {@code [Sprint Planning](vance:/…) — [Google](<google-url>) · [Outlook](<outlook-url>)}.
     * For Apple users the per-event button in the Web UI carries the
     * .ics download; we don't ship a `data:`-URI here because chat
     * pasting kilobyte-long URIs as link targets is fragile.
     */
    private static List<Map<String, Object>> buildAddLinks(List<CalendarEvent> events) {
        List<Map<String, Object>> out = new ArrayList<>(events.size());
        for (CalendarEvent ev : events) {
            CalendarLinkBuilder.Links links = CalendarLinkBuilder.buildLinks(ev);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("title", ev.title());
            if (links.google() != null) entry.put("google", links.google());
            if (links.outlook() != null) entry.put("outlook", links.outlook());
            out.add(entry);
        }
        return out;
    }

    // ── Event building ────────────────────────────────────────────

    private static CalendarEvent buildEvent(Map<String, Object> raw, int rowIndex) {
        String title = stringOrNull(raw.get("title"));
        if (title == null) {
            throw new ToolException(
                    "events[" + (rowIndex - 1) + "] is missing "
                            + "'title' — required for every event.");
        }
        String start = stringOrNull(raw.get("start"));
        if (start == null) {
            throw new ToolException(
                    "events[" + (rowIndex - 1) + "] ('" + title
                            + "') is missing 'start' — every event "
                            + "needs an ISO-8601 anchor date/time.");
        }
        String idStr = stringOrNull(raw.get("id"));
        String id = idStr != null ? idStr : UUID.randomUUID().toString();
        String end = stringOrNull(raw.get("end"));
        boolean allDay = raw.get("allDay") instanceof Boolean b && b;
        String location = stringOrNull(raw.get("location"));
        String recurrence = stringOrNull(raw.get("recurrence"));
        String color = stringOrNull(raw.get("color"));
        String notes = stringOrNull(raw.get("notes"));
        List<String> attendees = stringList(raw.get("attendees"));
        List<String> tags = stringList(raw.get("tags"));

        return new CalendarEvent(
                id, title, start, end, allDay,
                location, attendees, recurrence, color, tags, notes,
                new LinkedHashMap<>());
    }

    private static @Nullable String stringOrNull(@Nullable Object v) {
        if (v instanceof String s && !s.isBlank()) return s.trim();
        if (v != null && !(v instanceof String)) return v.toString();
        return null;
    }

    private static List<String> stringList(@Nullable Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            String s = stringOrNull(o);
            if (s != null) out.add(s);
        }
        return out;
    }

    // ── Path / title helpers ──────────────────────────────────────

    private static String deriveTitle(List<CalendarEvent> events) {
        if (events.isEmpty()) return "Calendar";
        // Use the first event's title only if it looks like a
        // calendar-level name (short, no time of day). Otherwise
        // fall back to a generic label.
        String first = events.get(0).title();
        if (first.length() <= 40) return first;
        return "Calendar";
    }

    static String defaultOutputPath(@Nullable String title) {
        String stamp = DateTimeFormatter
                .ofPattern("yyyy-MM-dd-HHmmss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        String slug = (title == null || title.isBlank())
                ? "calendar" : IcsToCalendarTool.slug(title);
        return "calendars/" + slug + "-" + stamp + ".yaml";
    }

    // ── Helpers ───────────────────────────────────────────────────

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

    private static boolean paramBoolean(@Nullable Map<String, Object> params, String key) {
        if (params == null) return false;
        Object v = params.get(key);
        return v instanceof Boolean b && b;
    }

    @SuppressWarnings("unchecked")
    private static @Nullable List<Map<String, Object>> paramMapList(
            @Nullable Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        if (!(v instanceof List<?> list)) return null;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                Map<String, Object> coerced = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (e.getKey() != null) {
                        coerced.put(e.getKey().toString(), e.getValue());
                    }
                }
                out.add(coerced);
            }
        }
        return out;
    }
}
