package de.mhus.vance.brain.applications.calendar;

import de.mhus.vance.api.calendar.CalendarArtefactSummary;
import de.mhus.vance.api.calendar.CalendarConflictView;
import de.mhus.vance.api.calendar.CalendarEventView;
import de.mhus.vance.api.calendar.CalendarLaneView;
import de.mhus.vance.api.calendar.CalendarPlannerView;
import de.mhus.vance.brain.tools.calendar.CalendarFolderReader;
import de.mhus.vance.brain.tools.calendar.CalendarLinkBuilder;
import de.mhus.vance.brain.tools.calendar.ConflictDetector;
import de.mhus.vance.brain.tools.calendar.RecurrenceExpander;
import de.mhus.vance.shared.document.kind.CalendarEvent;
import de.mhus.vance.shared.document.kind.CalendarsAppConfig;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the API-shaped {@link CalendarPlannerView} from a folder
 * scan. Stateless utility — no IO.
 */
public final class CalendarPlannerMapper {

    private CalendarPlannerMapper() {
        // utility class
    }

    public static CalendarPlannerView toView(CalendarFolderReader.Scan scan) {
        CalendarsAppConfig cfg = scan.calendarConfig();

        Map<String, Integer> countsByLane = new LinkedHashMap<>();
        for (CalendarFolderReader.CalendarFile cf : scan.calendars()) {
            countsByLane.merge(cf.lane(), cf.calendar().events().size(), Integer::sum);
        }

        List<CalendarLaneView> lanes = buildLanes(scan, cfg, countsByLane);
        List<CalendarEventView> events = buildEvents(scan);
        List<CalendarConflictView> conflicts = buildConflicts(scan);

        return CalendarPlannerView.builder()
                .folder(scan.folder())
                .manifestPath(scan.manifestDoc() != null ? scan.manifestDoc().getPath() : null)
                .title(scan.manifest().title())
                .description(scan.manifest().description())
                .windowFrom(cfg.window().from())
                .windowUntil(cfg.window().until())
                .lanes(lanes)
                .events(events)
                .conflicts(conflicts)
                .artefacts(new ArrayList<>())
                .build();
    }

    private static List<CalendarLaneView> buildLanes(
            CalendarFolderReader.Scan scan,
            CalendarsAppConfig cfg,
            Map<String, Integer> counts) {

        List<CalendarLaneView> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        // Declared lanes, sorted by `order:` then insertion.
        List<Map.Entry<String, CalendarsAppConfig.Lane>> declared =
                new ArrayList<>(cfg.lanes().entrySet());
        declared.sort(Comparator.comparingInt(
                e -> e.getValue().order() != null ? e.getValue().order() : Integer.MAX_VALUE));
        for (Map.Entry<String, CalendarsAppConfig.Lane> e : declared) {
            if (!seen.add(e.getKey())) continue;
            out.add(CalendarLaneView.builder()
                    .name(e.getKey())
                    .title(e.getValue().title() != null ? e.getValue().title() : e.getKey())
                    .color(e.getValue().color())
                    .order(e.getValue().order())
                    .eventCount(counts.getOrDefault(e.getKey(), 0))
                    .declared(true)
                    .sourcePath(scan.folder() + "/" + e.getKey() + "/work.yaml")
                    .build());
        }
        // Undeclared lanes that exist on disk (folders with calendar files
        // but no manifest entry). Auto-added to keep the UI honest.
        for (String laneName : counts.keySet()) {
            if (!seen.add(laneName)) continue;
            out.add(CalendarLaneView.builder()
                    .name(laneName)
                    .title(laneName)
                    .eventCount(counts.get(laneName))
                    .declared(false)
                    .sourcePath(scan.folder() + "/" + laneName + "/work.yaml")
                    .build());
        }
        return out;
    }

    private static List<CalendarEventView> buildEvents(CalendarFolderReader.Scan scan) {
        List<CalendarEventView> out = new ArrayList<>();
        for (CalendarFolderReader.CalendarFile cf : scan.calendars()) {
            for (CalendarEvent ev : cf.calendar().events()) {
                CalendarLinkBuilder.Links links = CalendarLinkBuilder.buildLinks(ev);
                out.add(CalendarEventView.builder()
                        .id(ev.id())
                        .lane(cf.lane())
                        .sourcePath(cf.doc().getPath())
                        .title(ev.title())
                        .start(ev.start())
                        .end(ev.end())
                        .allDay(ev.allDay())
                        .location(ev.location())
                        .attendees(new ArrayList<>(ev.attendees()))
                        .recurrence(ev.recurrence())
                        .color(ev.color())
                        .tags(new ArrayList<>(ev.tags()))
                        .notes(ev.notes())
                        .googleUrl(links.google())
                        .outlookUrl(links.outlook())
                        .build());
            }
        }
        return out;
    }

    private static List<CalendarConflictView> buildConflicts(CalendarFolderReader.Scan scan) {
        LocalDate today = LocalDate.now();
        LocalDate until = parseDate(scan.calendarConfig().window().until(), today.plusDays(180));
        LocalDateTime rangeStart = today.atStartOfDay();
        LocalDateTime rangeEnd = until.atTime(23, 59, 59);

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

        List<CalendarConflictView> out = new ArrayList<>();
        for (ConflictDetector.Conflict c : conflicts) {
            out.add(CalendarConflictView.builder()
                    .titleA(c.a().occurrence().event().title())
                    .laneA(c.a().lane())
                    .sourceA(c.a().sourcePath())
                    .titleB(c.b().occurrence().event().title())
                    .laneB(c.b().lane())
                    .sourceB(c.b().sourcePath())
                    .overlapStart(c.overlapStart().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                    .overlapEnd(c.overlapEnd().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                    .build());
        }
        return out;
    }

    private static LocalDate parseDate(String iso, LocalDate fallback) {
        if (iso == null || iso.isBlank()) return fallback;
        try { return LocalDate.parse(iso.trim()); }
        catch (Exception e) { return fallback; }
    }

    public static CalendarArtefactSummary artefactSummary(
            String name, String path, String markdownLink, String body, String mimeType) {
        return CalendarArtefactSummary.builder()
                .name(name).path(path).markdownLink(markdownLink)
                .body(body).mimeType(mimeType).build();
    }
}
