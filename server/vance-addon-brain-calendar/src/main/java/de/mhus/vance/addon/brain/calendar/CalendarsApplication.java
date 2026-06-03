package de.mhus.vance.addon.brain.calendar;

import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.brain.tools.document.DocumentLinkBuilder;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.kind.ApplicationCodec;
import de.mhus.vance.shared.document.kind.ApplicationDocument;
import de.mhus.vance.shared.document.kind.RecordsCodec;
import de.mhus.vance.shared.document.kind.RecordsDocument;
import de.mhus.vance.shared.document.kind.RecordsItem;
import de.mhus.vance.toolpack.ToolException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Concrete {@link VanceApplication} for {@code app: calendar} folders.
 * Owns the orchestration of every derived artefact: the Mermaid Gantt
 * ({@code _gantt.md}) and the conflict listing
 * ({@code _conflicts.yaml}).
 *
 * <p>Domain helpers ({@link CalendarFolderReader},
 * {@link ConflictDetector}, {@link GanttRenderer}) stay
 * stateless — this service knows how to wire them into one
 * idempotent refresh pipeline against {@link DocumentService}.
 *
 * <p>Public per-artefact methods ({@link #refreshConflicts},
 * {@link #refreshGantt}) let domain-specific tools regenerate one
 * artefact in isolation when the caller knows nothing else has
 * changed; {@link #refresh} regenerates everything.
 */
@Service
@Slf4j
public class CalendarsApplication implements VanceApplication {

    public static final String APP_NAME = CalendarsAppConfig.APP_NAME;

    /** Default lane name for events created without an explicit
     *  {@code lane:} field. Becomes a sub-folder under the suite root
     *  and a section in the Gantt. */
    public static final String COMMON_LANE = "common";

    private static final String YAML_MIME = "application/yaml";
    private static final String MD_MIME = "text/markdown";

    private static final List<String> CONFLICTS_SCHEMA = List.of(
            "title_a", "lane_a", "source_a",
            "title_b", "lane_b", "source_b",
            "overlap_start", "overlap_end");

    private final CalendarFolderReader folderReader;
    private final DocumentService documentService;
    private final DocumentLinkBuilder linkBuilder;

    public CalendarsApplication(CalendarFolderReader folderReader,
                                DocumentService documentService,
                                DocumentLinkBuilder linkBuilder) {
        this.folderReader = folderReader;
        this.documentService = documentService;
        this.linkBuilder = linkBuilder;
    }

    @Override public String appName() { return APP_NAME; }

    /**
     * One-shot bootstrap for a new calendar suite.
     *
     * <p>Writes the {@code _app.yaml} manifest with the correct
     * schema; if {@code events} are present in the params, also
     * dispatches them onto per-lane source files
     * ({@code <folder>/<lane>/work.yaml}) and runs an immediate
     * {@link #refresh} so {@code _gantt.md} and
     * {@code _conflicts.yaml} are available in the same call.
     *
     * <p>The LLM is expected to call this once with the entire plan
     * — manifest + events — and get back every artefact path.
     * Hand-stitching {@code _app.yaml}, then N {@code calendar_create}
     * calls, then {@code app_rebuild} is supported but unnecessary
     * for typical use.
     *
     * <p>Expected {@code params} keys:
     * <ul>
     *   <li>{@code title} (string, optional)</li>
     *   <li>{@code description} (string, optional)</li>
     *   <li>{@code lanes} (List of {@code {name, title?, color?, order?}}, optional —
     *       empty list = lanes inferred from events)</li>
     *   <li>{@code window} ({@code {from?, until?}}, optional)</li>
     *   <li>{@code events} (List of event maps, optional). Each event
     *       carries the regular {@link CalendarEvent} fields
     *       ({@code title}, {@code start}, {@code end}, {@code allDay},
     *       {@code recurrence}, {@code location}, {@code attendees},
     *       {@code tags}, {@code color}, {@code notes}) plus an
     *       optional {@code lane} field. Events without {@code lane}
     *       land in the {@value #COMMON_LANE} lane.</li>
     * </ul>
     */
    @Override
    public CreateResult create(CreateContext ctx) {
        String folder = ctx.folder();
        Map<String, Object> params = ctx.params() != null ? ctx.params() : new LinkedHashMap<>();
        String manifestPath = folder + "/" + CalendarFolderReader.APP_MANIFEST;

        Optional<DocumentDocument> existing = documentService.findByPath(
                ctx.tenantId(), ctx.projectName(), manifestPath);
        if (existing.isPresent() && !ctx.overwrite()) {
            throw new ToolException(
                    "Manifest already exists at '" + manifestPath
                            + "'. Pass overwrite=true to replace it.");
        }

        String title = asString(params.get("title"));
        String description = asString(params.get("description"));
        List<Map<String, Object>> laneInputs = asLaneInputList(params.get("lanes"));
        Map<String, Object> windowInput = asMap(params.get("window"));
        List<Map<String, Object>> eventInputs = asMapList(params.get("events"));

        // Build lane Map preserving caller-supplied order. Auto-
        // generate an `order:` when caller didn't supply one.
        Map<String, Object> lanes = new LinkedHashMap<>();
        List<CreateLane> laneResults = new java.util.ArrayList<>();
        int autoOrder = 1;
        for (Map<String, Object> raw : laneInputs) {
            String name = asString(raw.get("name"));
            if (name == null || name.isBlank()) continue;
            name = sanitiseLaneName(name);
            String laneTitle = asString(raw.get("title"));
            String laneColor = asString(raw.get("color"));
            Integer order = (raw.get("order") instanceof Number n) ? n.intValue() : autoOrder;

            Map<String, Object> laneBody = new LinkedHashMap<>();
            if (laneTitle != null) laneBody.put("title", laneTitle);
            if (laneColor != null) laneBody.put("color", laneColor);
            laneBody.put("order", order);
            lanes.put(name, laneBody);

            laneResults.add(new CreateLane(
                    name, laneTitle, laneColor,
                    folder + "/" + name + "/work.yaml"));
            autoOrder = order + 1;
        }

        // Group inline events by lane (default = COMMON_LANE).
        // Auto-extend the lanes map with any lane referenced by an
        // event but missing from `laneInputs` — saves the caller from
        // duplicating lane names between params.
        Map<String, List<Map<String, Object>>> eventsByLane = new LinkedHashMap<>();
        for (Map<String, Object> raw : eventInputs) {
            String laneRaw = asString(raw.get("lane"));
            String lane = (laneRaw == null || laneRaw.isBlank())
                    ? COMMON_LANE : sanitiseLaneName(laneRaw);
            Map<String, Object> stripped = new LinkedHashMap<>(raw);
            stripped.remove("lane");
            eventsByLane.computeIfAbsent(lane, k -> new java.util.ArrayList<>())
                    .add(stripped);
        }
        for (String laneName : eventsByLane.keySet()) {
            if (lanes.containsKey(laneName)) continue;
            Map<String, Object> laneBody = new LinkedHashMap<>();
            laneBody.put("order", autoOrder);
            lanes.put(laneName, laneBody);
            laneResults.add(new CreateLane(
                    laneName, null, null,
                    folder + "/" + laneName + "/work.yaml"));
            autoOrder++;
        }

        // Build the calendar config block.
        Map<String, Object> calendarBlock = new LinkedHashMap<>();
        if (windowInput != null && !windowInput.isEmpty()) {
            Map<String, Object> window = new LinkedHashMap<>();
            String from = asString(windowInput.get("from"));
            String until = asString(windowInput.get("until"));
            if (from != null) window.put("from", from);
            if (until != null) window.put("until", until);
            if (!window.isEmpty()) calendarBlock.put("window", window);
        }
        if (!lanes.isEmpty()) calendarBlock.put("lanes", lanes);
        calendarBlock.put("gantt", Map.of(
                "outputPath", "_gantt.md",
                "includeRecurring", false));
        calendarBlock.put("conflicts", Map.of(
                "outputPath", "_conflicts.yaml"));

        // Assemble ApplicationDocument and serialise.
        Map<String, Object> appConfig = new LinkedHashMap<>();
        appConfig.put(CalendarsAppConfig.APP_NAME, calendarBlock);
        ApplicationDocument manifest = new ApplicationDocument(
                "application", APP_NAME, title, description,
                appConfig, new LinkedHashMap<>());
        String body = ApplicationCodec.serialize(manifest, YAML_MIME);

        DocumentDocument stored;
        if (existing.isPresent()) {
            stored = documentService.update(
                    existing.get().getId(),
                    title != null ? title : "Calendar app",
                    List.of("application", "calendar"),
                    body, null, null, null, null, YAML_MIME);
        } else {
            try (var in = new java.io.ByteArrayInputStream(
                    body.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                stored = documentService.create(
                        ctx.tenantId(), ctx.projectName(),
                        manifestPath,
                        title != null ? title : "Calendar app",
                        List.of("application", "calendar"),
                        YAML_MIME, in, ctx.userId());
            } catch (java.io.IOException e) {
                throw new ToolException(
                        "Could not write manifest '" + manifestPath
                                + "': " + e.getMessage());
            }
        }

        // Dispatch inline events onto per-lane source files. Each
        // distinct lane gets one `<folder>/<lane>/work.yaml` with
        // all its events.
        int eventCountWritten = 0;
        for (Map.Entry<String, List<Map<String, Object>>> e : eventsByLane.entrySet()) {
            String lane = e.getKey();
            List<Map<String, Object>> rawEvents = e.getValue();
            if (rawEvents.isEmpty()) continue;
            String laneFilePath = folder + "/" + lane + "/work.yaml";
            CalendarDocument calDoc = buildCalendarDocument(rawEvents);
            String calBody = CalendarCodec.serialize(calDoc, YAML_MIME);
            writeOrUpdateCalendar(ctx, laneFilePath, calBody,
                    "Calendar — " + lane);
            eventCountWritten += calDoc.events().size();
        }

        log.info("CalendarsApplication.create tenant='{}' folder='{}' "
                        + "lanes={} events={} manifestPath='{}'",
                ctx.tenantId(), folder, laneResults.size(),
                eventCountWritten, manifestPath);

        // Auto-refresh when inline events were dispatched — saves
        // the caller from doing app_rebuild() in a second tool call.
        List<ArtefactResult> artefacts;
        if (eventCountWritten > 0) {
            RefreshContext rc = new RefreshContext(
                    ctx.tenantId(), ctx.projectName(), folder,
                    ctx.userId(), ctx.processId());
            RefreshResult refresh = refresh(rc);
            artefacts = refresh.artefacts();
        } else {
            artefacts = List.of();
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("laneCount", laneResults.size());
        stats.put("manifestLaneCount", lanes.size());
        if (eventCountWritten > 0) stats.put("eventCount", eventCountWritten);
        if (title != null) stats.put("title", title);

        String nextStep;
        if (eventCountWritten > 0) {
            nextStep = "App is ready — Gantt + Conflicts are in the "
                    + "`artefacts` list. Embed both `markdownLink`s "
                    + "in your chat reply so the user can open them.";
        } else if (!laneResults.isEmpty()) {
            nextStep = "For each lane, call "
                    + "`calendar_create(outputPath=<suggestedFilePath>, "
                    + "events=[…])` using the paths above, then "
                    + "`app_rebuild('" + folder + "')` to generate "
                    + "the Gantt + conflicts. Or pass `events=[…]` "
                    + "to `calendar_app_create` directly next time "
                    + "for a one-shot setup.";
        } else {
            nextStep = "Add events directly via "
                    + "`calendar_app_create(folder, lanes=[…], events=[…])` "
                    + "for a one-shot setup, or `calendar_create` per lane.";
        }

        return new CreateResult(
                APP_NAME, folder, stored.getPath(),
                linkBuilder.linkFor(stored, ctx.projectName()),
                laneResults, artefacts, nextStep, stats);
    }

    /**
     * Build a {@link CalendarDocument} from raw event maps. Each
     * event must carry at least {@code title} and {@code start};
     * other fields are optional. Throws {@link ToolException} on
     * missing required fields so the caller knows exactly which
     * event was malformed.
     */
    private static CalendarDocument buildCalendarDocument(List<Map<String, Object>> raws) {
        List<CalendarEvent> events = new java.util.ArrayList<>(raws.size());
        for (int i = 0; i < raws.size(); i++) {
            Map<String, Object> raw = raws.get(i);
            String evTitle = asString(raw.get("title"));
            if (evTitle == null) {
                throw new ToolException(
                        "events[" + i + "] missing 'title'");
            }
            String evStart = asString(raw.get("start"));
            if (evStart == null) {
                throw new ToolException(
                        "events[" + i + "] ('" + evTitle + "') missing 'start'");
            }
            String evId = asString(raw.get("id"));
            if (evId == null) evId = UUID.randomUUID().toString();
            String end = asString(raw.get("end"));
            boolean allDay = raw.get("allDay") instanceof Boolean b && b;
            String location = asString(raw.get("location"));
            String recurrence = asString(raw.get("recurrence"));
            String color = asString(raw.get("color"));
            String notes = asString(raw.get("notes"));
            List<String> attendees = asStringList(raw.get("attendees"));
            List<String> tags = asStringList(raw.get("tags"));
            events.add(new CalendarEvent(
                    evId, evTitle, evStart, end, allDay, location,
                    attendees, recurrence, color, tags, notes,
                    new LinkedHashMap<>()));
        }
        return new CalendarDocument("calendar", events, new LinkedHashMap<>());
    }

    /** Idempotent write of a calendar source file inside a suite folder. */
    private void writeOrUpdateCalendar(CreateContext ctx, String path,
                                       String body, String title) {
        Optional<DocumentDocument> existing = documentService.findByPath(
                ctx.tenantId(), ctx.projectName(), path);
        if (existing.isPresent()) {
            documentService.update(
                    existing.get().getId(),
                    title, List.of("calendar"),
                    body, null, null, null, null, YAML_MIME);
            return;
        }
        try (ByteArrayInputStream in = new ByteArrayInputStream(
                body.getBytes(StandardCharsets.UTF_8))) {
            documentService.create(
                    ctx.tenantId(), ctx.projectName(),
                    path, title, List.of("calendar"),
                    YAML_MIME, in, ctx.userId());
        } catch (IOException e) {
            throw new ToolException(
                    "Could not write calendar '" + path + "': " + e.getMessage());
        }
    }

    @Override
    public RefreshResult refresh(RefreshContext ctx) {
        CalendarFolderReader.Scan scan = folderReader.scan(
                ctx.tenantId(), ctx.projectName(), ctx.folder());

        ArtefactResult conflicts = doRefreshConflicts(scan, ctx, null, null);
        ArtefactResult gantt = doRefreshGantt(scan, ctx, null, null);

        log.info("CalendarsApplication.refresh tenant='{}' folder='{}' "
                        + "→ {} + {}",
                ctx.tenantId(), scan.folder(),
                conflicts.path(), gantt.path());

        return new RefreshResult(APP_NAME, scan.folder(),
                List.of(conflicts, gantt));
    }

    // ── Single-artefact refresh paths ─────────────────────────────

    /**
     * Regenerate only the conflicts artefact. Used by the
     * {@code calendar_conflicts} tool when the user explicitly wants
     * to update just that listing without touching the Gantt.
     *
     * @param from null = today; the manifest's {@code calendar.window}
     *             is not used here because the caller may want a
     *             one-off scan window. Use {@link #refresh} when the
     *             manifest defaults should apply.
     * @param to   null = 180 days from today.
     */
    public ArtefactResult refreshConflicts(RefreshContext ctx,
                                           @Nullable LocalDate from,
                                           @Nullable LocalDate to) {
        CalendarFolderReader.Scan scan = folderReader.scan(
                ctx.tenantId(), ctx.projectName(), ctx.folder());
        return doRefreshConflicts(scan, ctx, from, to);
    }

    /**
     * Regenerate only the Gantt artefact. Used by the
     * {@code gantt_from_calendars} tool.
     */
    public ArtefactResult refreshGantt(RefreshContext ctx,
                                       @Nullable LocalDate from,
                                       @Nullable LocalDate to) {
        CalendarFolderReader.Scan scan = folderReader.scan(
                ctx.tenantId(), ctx.projectName(), ctx.folder());
        return doRefreshGantt(scan, ctx, from, to);
    }

    // ── Internal: conflicts ───────────────────────────────────────

    private ArtefactResult doRefreshConflicts(CalendarFolderReader.Scan scan,
                                              RefreshContext ctx,
                                              @Nullable LocalDate fromOverride,
                                              @Nullable LocalDate toOverride) {
        LocalDate today = LocalDate.now();
        LocalDate from = fromOverride != null ? fromOverride : today;
        LocalDate to = toOverride != null
                ? toOverride
                : manifestUntil(scan).orElse(today.plusDays(180));
        if (from.isAfter(to)) {
            throw new ToolException(
                    "Conflict scan window 'from' (" + from + ") is after 'to' (" + to + ").");
        }
        LocalDateTime rangeStart = from.atStartOfDay();
        LocalDateTime rangeEnd = to.atTime(23, 59, 59);

        // Expand events into the window.
        List<ConflictDetector.LocatedOccurrence> occs = new ArrayList<>();
        for (CalendarFolderReader.CalendarFile cf : scan.calendars()) {
            for (CalendarEvent ev : cf.calendar().events()) {
                for (RecurrenceExpander.Occurrence occ :
                        RecurrenceExpander.expand(ev, rangeStart, rangeEnd)) {
                    occs.add(new ConflictDetector.LocatedOccurrence(
                            occ, cf.lane(), cf.doc().getPath()));
                }
            }
        }
        List<ConflictDetector.Conflict> conflicts = ConflictDetector.detect(
                occs, scan.calendarConfig().conflicts());

        // Render as kind: records body.
        List<RecordsItem> items = new ArrayList<>(conflicts.size());
        for (ConflictDetector.Conflict c : conflicts) {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("title_a", c.a().occurrence().event().title());
            values.put("lane_a", c.a().lane());
            values.put("source_a", c.a().sourcePath());
            values.put("title_b", c.b().occurrence().event().title());
            values.put("lane_b", c.b().lane());
            values.put("source_b", c.b().sourcePath());
            values.put("overlap_start", c.overlapStart()
                    .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            values.put("overlap_end", c.overlapEnd()
                    .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            items.add(new RecordsItem(values, new LinkedHashMap<>(), new ArrayList<>()));
        }
        RecordsDocument records = new RecordsDocument(
                "records", CONFLICTS_SCHEMA, items, new LinkedHashMap<>());
        String body = RecordsCodec.serialize(records, YAML_MIME);

        String outputPath = CalendarFolderReader.resolveOutputPath(
                scan.folder(), scan.calendarConfig().conflicts().outputPath());
        String title = "Calendar conflicts (" + scan.folder() + ")";
        DocumentDocument stored = writeArtefact(
                ctx, outputPath, body, title, YAML_MIME,
                List.of("calendar", "generated", "conflicts"));

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("conflictCount", conflicts.size());
        stats.put("eventCount", occs.size());
        stats.put("from", from.toString());
        stats.put("to", to.toString());

        return new ArtefactResult(
                "conflicts", stored.getPath(),
                linkBuilder.linkFor(stored, ctx.projectName()),
                stats);
    }

    // ── Internal: gantt ───────────────────────────────────────────

    private ArtefactResult doRefreshGantt(CalendarFolderReader.Scan scan,
                                          RefreshContext ctx,
                                          @Nullable LocalDate fromOverride,
                                          @Nullable LocalDate toOverride) {
        LocalDate from = fromOverride != null
                ? fromOverride
                : GanttRenderer.parseDate(scan.calendarConfig().window().from());
        LocalDate to = toOverride != null
                ? toOverride
                : GanttRenderer.parseDate(scan.calendarConfig().window().until());

        String fallbackTitle = leafFolderName(scan.folder());
        String mermaidSource = GanttRenderer.render(scan, fallbackTitle, from, to);
        String title = scan.manifest().title() != null
                ? scan.manifest().title() : fallbackTitle;
        String body = GanttRenderer.wrapAsDiagramMarkdown(mermaidSource, title);

        String outputPath = CalendarFolderReader.resolveOutputPath(
                scan.folder(), scan.calendarConfig().gantt().outputPath());
        DocumentDocument stored = writeArtefact(
                ctx, outputPath, body, "Gantt — " + title, MD_MIME,
                List.of("calendar", "generated", "gantt"));

        // laneCount = lanes actually rendered (= sub-folders that
        // carry calendar files), not the count of declared lanes in
        // the manifest. The two diverge whenever the LLM dispatches
        // events into a lane that isn't (yet) listed in `_app.yaml`.
        java.util.Set<String> renderedLanes = new java.util.LinkedHashSet<>();
        for (CalendarFolderReader.CalendarFile cf : scan.calendars()) {
            renderedLanes.add(cf.lane());
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("eventCount", countEligibleEvents(scan));
        stats.put("laneCount", renderedLanes.size());
        if (renderedLanes.size() != scan.calendarConfig().lanes().size()) {
            stats.put("manifestLaneCount", scan.calendarConfig().lanes().size());
        }
        if (from != null) stats.put("from", from.toString());
        if (to != null) stats.put("to", to.toString());

        return new ArtefactResult(
                "gantt", stored.getPath(),
                linkBuilder.linkFor(stored, ctx.projectName()),
                stats);
    }

    // ── Common write path ─────────────────────────────────────────

    private DocumentDocument writeArtefact(RefreshContext ctx,
                                           String outputPath,
                                           String body,
                                           String title,
                                           String mime,
                                           List<String> tags) {
        Optional<DocumentDocument> existing = documentService.findByPath(
                ctx.tenantId(), ctx.projectName(), outputPath);
        if (existing.isPresent()) {
            return documentService.update(
                    existing.get().getId(),
                    title, tags, body, null, null, null, null, mime);
        }
        try (InputStream in = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))) {
            return documentService.create(
                    ctx.tenantId(), ctx.projectName(),
                    outputPath, title, tags, mime, in, ctx.userId());
        } catch (IOException e) {
            throw new ToolException(
                    "Could not write artefact '" + outputPath + "': " + e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────

    private static Optional<LocalDate> manifestUntil(CalendarFolderReader.Scan scan) {
        return Optional.ofNullable(
                GanttRenderer.parseDate(scan.calendarConfig().window().until()));
    }

    private static int countEligibleEvents(CalendarFolderReader.Scan scan) {
        int count = 0;
        boolean includeRecurring = scan.calendarConfig().gantt().includeRecurring();
        List<String> tagFilter = scan.calendarConfig().gantt().tagFilter();
        for (CalendarFolderReader.CalendarFile cf : scan.calendars()) {
            for (CalendarEvent ev : cf.calendar().events()) {
                if (!includeRecurring && ev.recurrence() != null
                        && !ev.recurrence().isBlank()) continue;
                if (!tagFilter.isEmpty()) {
                    boolean any = false;
                    for (String t : tagFilter) {
                        if (ev.tags().contains(t)) { any = true; break; }
                    }
                    if (!any) continue;
                }
                count++;
            }
        }
        return count;
    }

    private static String leafFolderName(String folder) {
        int slash = folder.lastIndexOf('/');
        return slash < 0 ? folder : folder.substring(slash + 1).toLowerCase(Locale.ROOT);
    }

    // ── create() param helpers ─────────────────────────────────────

    private static @Nullable String asString(@Nullable Object v) {
        if (v instanceof String s && !s.isBlank()) return s.trim();
        if (v != null && !(v instanceof String)) return v.toString();
        return null;
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Map<String, Object> asMap(@Nullable Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null) out.put(e.getKey().toString(), e.getValue());
            }
            return out;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asMapList(@Nullable Object v) {
        if (!(v instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Object item : list) {
            Map<String, Object> m = asMap(item);
            if (m != null) out.add(m);
        }
        return out;
    }

    private static List<String> asStringList(@Nullable Object v) {
        if (!(v instanceof List<?> list)) return List.of();
        List<String> out = new java.util.ArrayList<>();
        for (Object item : list) {
            String s = asString(item);
            if (s != null) out.add(s);
        }
        return out;
    }

    /**
     * Permissive lane-list reader: accepts either rich objects
     * ({@code [{name:..., title:..., color:..., order:...}, ...]})
     * or plain strings ({@code ["design", "backend"]}). The string
     * shorthand is what LLMs reliably produce when the user said
     * "Lanes: Design, Backend, Frontend"; we shouldn't punish that
     * by silently dropping the lanes. Each string lane becomes
     * {@code {name: <string>}} with defaults filled in downstream.
     */
    private static List<Map<String, Object>> asLaneInputList(@Nullable Object v) {
        if (!(v instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Object item : list) {
            if (item instanceof String s && !s.isBlank()) {
                Map<String, Object> wrapped = new LinkedHashMap<>();
                wrapped.put("name", s.trim());
                out.add(wrapped);
            } else {
                Map<String, Object> m = asMap(item);
                if (m != null) out.add(m);
            }
        }
        return out;
    }

    /** Lane names land in folder paths; restrict to filesystem-safe
     *  chars. Anything outside [a-z0-9_-] becomes '-', collapsed. */
    private static String sanitiseLaneName(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.toLowerCase(Locale.ROOT).toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                sb.append(c);
            } else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '-') {
                sb.append('-');
            }
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') sb.setLength(sb.length() - 1);
        return sb.length() == 0 ? "lane" : sb.toString();
    }
}
