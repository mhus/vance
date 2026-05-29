package de.mhus.vance.brain.applications;

import de.mhus.vance.brain.tools.calendar.CalendarFolderReader;
import de.mhus.vance.brain.tools.calendar.ConflictDetector;
import de.mhus.vance.brain.tools.calendar.GanttRenderer;
import de.mhus.vance.brain.tools.calendar.RecurrenceExpander;
import de.mhus.vance.brain.tools.document.DocumentLinkBuilder;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.kind.CalendarEvent;
import de.mhus.vance.shared.document.kind.CalendarsAppConfig;
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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("eventCount", countEligibleEvents(scan));
        stats.put("laneCount", scan.calendarConfig().lanes().size());
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
}
