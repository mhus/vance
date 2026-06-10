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
 * Convert an iCalendar (RFC 5545) source into a Vance
 * {@code kind: calendar} document.
 *
 * <p>Two ways to supply input — mutually exclusive:
 * <ul>
 *   <li>{@code documentRef} — path or id of an existing Vance Document
 *       holding the {@code .ics} body. Use this when the user uploaded
 *       a calendar invite as a Vance Document first.</li>
 *   <li>{@code icsBody} — raw ICS text inline. Use this when the LLM
 *       has the text in hand (pasted from chat, extracted from an
 *       email).</li>
 * </ul>
 *
 * <p>The result is a fresh {@code kind: calendar} YAML document the
 * user can open with the regular Calendar view (month / agenda).
 *
 * <p>The parser is a deliberate RFC 5545 subset — it understands the
 * common {@code VEVENT} fields (UID, SUMMARY, DTSTART, DTEND,
 * LOCATION, DESCRIPTION, RRULE, ATTENDEE, ORGANIZER, CATEGORIES) plus
 * line-folding and the standard backslash escapes ({@code \\n},
 * {@code \\,}, {@code \\;}, {@code \\\\}). Exotic constructs
 * (VTIMEZONE blocks, ATTACH, X-WR-* extensions) are silently skipped;
 * the imported calendar always renders the events it could read,
 * even when the source carries fields we don't model.
 */
@Component
@Slf4j
public class IcsToCalendarTool implements Tool {

    private static final String YAML_MIME = "application/yaml";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("icsBody", Map.of(
                        "type", "string",
                        "description", "Raw iCalendar (.ics) source. "
                                + "Use this when the LLM has the text "
                                + "in hand. Mutually exclusive with "
                                + "'documentRef'."));
                put("documentRef", Map.of(
                        "type", "string",
                        "description", "Path or id of an existing "
                                + "Vance Document holding the .ics "
                                + "body. Mutually exclusive with "
                                + "'icsBody'."));
                put("title", Map.of(
                        "type", "string",
                        "description", "Optional title for the imported "
                                + "calendar — used in the document "
                                + "header and to derive the filename. "
                                + "Defaults to 'Imported calendar'."));
                put("outputPath", Map.of(
                        "type", "string",
                        "description", "Optional path for the new "
                                + "Calendar document. Default: "
                                + "'calendars/<title-slug>-<timestamp>"
                                + ".yaml'."));
                put("projectId", Map.of(
                        "type", "string",
                        "description", "Optional project name; "
                                + "defaults to the active project."));
            }},
            "required", List.of());

    private final EddieContext eddieContext;
    private final DocumentService documentService;
    private final DocumentLinkBuilder linkBuilder;
    private final ThinkProcessService thinkProcessService;
    private final ProgressEmitter progressEmitter;

    public IcsToCalendarTool(EddieContext eddieContext,
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

    @Override public String name() { return "ics_to_calendar"; }

    @Override
    public String description() {
        return "Import an iCalendar (.ics) source into a Vance "
                + "kind:calendar document the user can browse with "
                + "month and agenda views. Reads UID / SUMMARY / "
                + "DTSTART / DTEND / LOCATION / DESCRIPTION / RRULE / "
                + "ATTENDEE / CATEGORIES from each VEVENT. Returns "
                + "the created Document plus a `markdownLink` to paste "
                + "back into chat.";
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
        String icsBody = paramString(params, "icsBody");
        if (documentRef != null && icsBody != null) {
            throw new ToolException(
                    "Provide either 'icsBody' OR 'documentRef', not both");
        }
        if (documentRef == null && icsBody == null) {
            throw new ToolException(
                    "Provide either 'icsBody' or 'documentRef'");
        }

        String title = paramString(params, "title");
        String outputPath = paramString(params, "outputPath");

        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        String projectName = project.getName();
        ThinkProcessDocument process = loadProcess(ctx);

        String source;
        String sourceLabel;
        if (icsBody != null) {
            source = icsBody;
            sourceLabel = "inline";
        } else {
            DocumentDocument doc = resolveSourceDoc(documentRef, projectName, ctx);
            source = loadAsText(doc);
            sourceLabel = doc.getPath();
            if (title == null) {
                title = doc.getTitle() != null
                        ? doc.getTitle() : leafName(doc.getPath());
            }
        }
        String effectiveTitle = title != null ? title : "Imported calendar";

        emit(process, StatusTag.INFO, "Parsing iCalendar source…");

        long started = System.currentTimeMillis();
        List<CalendarEvent> events = parseIcs(source);
        long elapsedMs = System.currentTimeMillis() - started;

        if (events.isEmpty()) {
            throw new ToolException(
                    "No VEVENT blocks found in the iCalendar source — "
                            + "is this really an .ics file? The first "
                            + "non-blank line should be "
                            + "'BEGIN:VCALENDAR'.");
        }

        CalendarDocument cal = new CalendarDocument(
                "calendar", events, new LinkedHashMap<>());
        String yaml = CalendarCodec.serialize(cal, YAML_MIME);

        String finalPath = outputPath != null
                ? outputPath
                : defaultOutputPath(effectiveTitle);

        DocumentDocument created;
        try (InputStream in = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))) {
            created = documentService.create(
                    ctx.tenantId(),
                    projectName,
                    finalPath,
                    effectiveTitle,
                    List.of("calendar", "imported"),
                    YAML_MIME,
                    in,
                    ctx.userId());
        } catch (IOException e) {
            throw new ToolException(
                    "Could not store imported calendar: " + e.getMessage());
        }

        String vanceUri = DocumentLinkBuilder.buildVanceUri(
                null, created.getPath(), "calendar",
                DocumentLinkBuilder.defaultModeForKind("calendar"));
        String markdownLink = linkBuilder.linkFor(created, projectName);

        log.info("IcsToCalendarTool tenant='{}' source='{}' events={} "
                        + "elapsedMs={} path='{}'",
                ctx.tenantId(), sourceLabel, events.size(),
                elapsedMs, finalPath);
        emit(process, StatusTag.INFO,
                String.format(Locale.ROOT,
                        "Imported %d events into '%s'.",
                        events.size(), finalPath));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", created.getPath());
        out.put("eventCount", events.size());
        out.put("size", created.getSize());
        out.put("elapsedMs", elapsedMs);
        out.put("vanceUri", vanceUri);
        out.put("markdownLink", markdownLink);
        return out;
    }

    // ── ICS parsing ───────────────────────────────────────────────

    /**
     * Parse the iCalendar source and return one {@link CalendarEvent}
     * per {@code VEVENT} block. The parser is intentionally
     * permissive — unknown properties are skipped, malformed values
     * default sensibly (missing UID → fresh UUID, missing SUMMARY →
     * "Untitled event"). Events without a parseable {@code DTSTART}
     * are dropped (no anchor date means we can't place them on the
     * calendar at all).
     */
    static List<CalendarEvent> parseIcs(String source) {
        List<String> lines = unfoldLines(source);
        List<CalendarEvent> events = new ArrayList<>();
        Map<String, String> current = null;
        List<String> currentAttendees = null;
        boolean inEvent = false;

        for (String line : lines) {
            String upper = line.toUpperCase(Locale.ROOT);
            if (upper.startsWith("BEGIN:VEVENT")) {
                current = new LinkedHashMap<>();
                currentAttendees = new ArrayList<>();
                inEvent = true;
                continue;
            }
            if (upper.startsWith("END:VEVENT")) {
                if (current != null) {
                    CalendarEvent ev = buildEvent(current, currentAttendees);
                    if (ev != null) events.add(ev);
                }
                current = null;
                currentAttendees = null;
                inEvent = false;
                continue;
            }
            if (!inEvent || current == null) continue;

            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String nameWithParams = line.substring(0, colon);
            String value = line.substring(colon + 1);

            int semi = nameWithParams.indexOf(';');
            String propName;
            String params;
            if (semi < 0) {
                propName = nameWithParams.toUpperCase(Locale.ROOT);
                params = "";
            } else {
                propName = nameWithParams.substring(0, semi).toUpperCase(Locale.ROOT);
                params = nameWithParams.substring(semi + 1);
            }

            switch (propName) {
                case "UID", "SUMMARY", "LOCATION", "DESCRIPTION",
                     "RRULE", "CATEGORIES", "ORGANIZER" ->
                        current.put(propName, value);
                case "DTSTART", "DTEND" -> {
                    // Carry the VALUE=DATE marker alongside the value
                    // so buildEvent can decide allDay correctly.
                    boolean isDateOnly = params.toUpperCase(Locale.ROOT).contains("VALUE=DATE")
                            && !params.toUpperCase(Locale.ROOT).contains("VALUE=DATE-TIME");
                    current.put(propName, (isDateOnly ? "D|" : "T|") + value);
                }
                case "ATTENDEE" -> {
                    if (currentAttendees != null) {
                        String pretty = extractAttendee(params, value);
                        if (pretty != null) currentAttendees.add(pretty);
                    }
                }
                default -> { /* ignored — unknown property */ }
            }
        }
        return events;
    }

    /**
     * RFC 5545 line folding: any line that begins with a space or
     * horizontal tab is a continuation of the previous line, with the
     * leading whitespace stripped. We also tolerate CRLF / LF / CR
     * line endings.
     */
    static List<String> unfoldLines(String source) {
        String normalised = source.replace("\r\n", "\n").replace("\r", "\n");
        String[] raw = normalised.split("\n", -1);
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean first = true;
        for (String line : raw) {
            if (!line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '\t')) {
                current.append(line, 1, line.length());
            } else {
                if (!first) out.add(current.toString());
                current.setLength(0);
                current.append(line);
                first = false;
            }
        }
        if (!first) out.add(current.toString());
        return out;
    }

    private static @Nullable CalendarEvent buildEvent(
            Map<String, String> props, List<String> attendees) {
        String dtStartRaw = props.get("DTSTART");
        if (dtStartRaw == null) return null;

        boolean dtStartAllDay = dtStartRaw.startsWith("D|");
        String dtStartValue = dtStartRaw.substring(2);
        String start = parseIcsDate(dtStartValue, dtStartAllDay);
        if (start == null) return null;

        String dtEndRaw = props.get("DTEND");
        String end = null;
        boolean dtEndAllDay = false;
        if (dtEndRaw != null) {
            dtEndAllDay = dtEndRaw.startsWith("D|");
            end = parseIcsDate(dtEndRaw.substring(2), dtEndAllDay);
        }

        boolean allDay = dtStartAllDay || dtEndAllDay;

        String title = unescapeText(props.getOrDefault("SUMMARY", "Untitled event"));
        String location = props.containsKey("LOCATION")
                ? unescapeText(props.get("LOCATION")) : null;
        String notes = props.containsKey("DESCRIPTION")
                ? unescapeText(props.get("DESCRIPTION")) : null;
        String rrule = props.containsKey("RRULE")
                ? props.get("RRULE").trim() : null;

        List<String> tags = new ArrayList<>();
        if (props.containsKey("CATEGORIES")) {
            for (String c : unescapeText(props.get("CATEGORIES")).split(",")) {
                String trimmed = c.trim();
                if (!trimmed.isEmpty()) tags.add(trimmed);
            }
        }

        String uid = props.get("UID");
        if (uid == null || uid.isBlank()) uid = UUID.randomUUID().toString();

        return new CalendarEvent(
                uid,
                title,
                start,
                end,
                allDay,
                location,
                attendees == null ? List.of() : new ArrayList<>(attendees),
                rrule,
                null,
                tags,
                notes,
                new LinkedHashMap<>());
    }

    /**
     * Convert an RFC 5545 date/date-time literal into the ISO-8601
     * string our CalendarCodec stores. Recognised forms:
     * <ul>
     *   <li>{@code 20260612}            → {@code 2026-06-12}</li>
     *   <li>{@code 20260612T090000}     → {@code 2026-06-12T09:00:00}</li>
     *   <li>{@code 20260612T090000Z}    → {@code 2026-06-12T09:00:00Z}</li>
     * </ul>
     * TZID parameters are ignored at this layer — the date-time goes
     * out as a local-time string and the viewer renders in the user's
     * timezone. Full timezone support is v2.
     */
    static @Nullable String parseIcsDate(String value, boolean dateOnly) {
        if (value == null) return null;
        String v = value.trim();
        if (v.isEmpty()) return null;
        if (dateOnly && v.length() >= 8) {
            return v.substring(0, 4) + "-" + v.substring(4, 6) + "-" + v.substring(6, 8);
        }
        if (v.length() >= 15 && v.charAt(8) == 'T') {
            String date = v.substring(0, 4) + "-" + v.substring(4, 6) + "-" + v.substring(6, 8);
            String time = v.substring(9, 11) + ":" + v.substring(11, 13) + ":" + v.substring(13, 15);
            boolean utc = v.length() >= 16 && v.charAt(v.length() - 1) == 'Z';
            return date + "T" + time + (utc ? "Z" : "");
        }
        if (v.length() >= 8 && !v.contains("T")) {
            return v.substring(0, 4) + "-" + v.substring(4, 6) + "-" + v.substring(6, 8);
        }
        return null;
    }

    /**
     * Pretty-print an ATTENDEE line. Picks the {@code CN=} parameter
     * (Common Name) if present, otherwise strips the {@code mailto:}
     * prefix from the value. Falls back to the raw value.
     */
    static @Nullable String extractAttendee(String params, String value) {
        if (params != null && !params.isEmpty()) {
            for (String p : params.split(";")) {
                String upper = p.trim().toUpperCase(Locale.ROOT);
                if (upper.startsWith("CN=")) {
                    String cn = p.trim().substring(3);
                    if (cn.startsWith("\"") && cn.endsWith("\"") && cn.length() >= 2) {
                        cn = cn.substring(1, cn.length() - 1);
                    }
                    if (!cn.isBlank()) return unescapeText(cn);
                }
            }
        }
        if (value == null) return null;
        String v = value.trim();
        if (v.toLowerCase(Locale.ROOT).startsWith("mailto:")) {
            v = v.substring(7);
        }
        return v.isBlank() ? null : v;
    }

    /**
     * Unescape the RFC 5545 backslash sequences:
     * {@code \\n} / {@code \\N} → newline, {@code \\,} → comma,
     * {@code \\;} → semicolon, {@code \\\\} → backslash. Unknown
     * sequences keep the backslash so we don't corrupt user content.
     */
    static String unescapeText(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case 'n', 'N' -> { out.append('\n'); i++; }
                    case ',' -> { out.append(','); i++; }
                    case ';' -> { out.append(';'); i++; }
                    case '\\' -> { out.append('\\'); i++; }
                    default -> out.append(c);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
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

    private String loadAsText(DocumentDocument doc) {
        if (documentService.readContent(doc) != null) return documentService.readContent(doc);
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
        String slug = (title == null || title.isBlank()) ? "calendar" : slug(title);
        return "calendars/" + slug + "-" + stamp + ".yaml";
    }

    /** lowercase + replace non-alphanumeric with dashes + collapse + trim. */
    static String slug(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length());
        for (char c : lower.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) sb.append(c);
            else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '-') sb.append('-');
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') sb.setLength(sb.length() - 1);
        String result = sb.toString();
        return result.isEmpty() ? "calendar" : result;
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
