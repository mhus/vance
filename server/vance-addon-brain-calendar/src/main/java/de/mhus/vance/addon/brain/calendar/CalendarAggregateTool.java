package de.mhus.vance.addon.brain.calendar;

import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.addon.brain.calendar.CalendarEvent;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Aggregate query over a calendar-app folder. Reads the
 * {@code _app.yaml} manifest + every {@code kind: calendar} file
 * (recursive), optionally expands recurring events into the query
 * window, applies tag / lane filters, and returns the resulting flat
 * list of occurrences to the LLM.
 *
 * <p>No file is written. Use {@code gantt_from_calendars} or
 * {@code calendar_conflicts} for the file-producing variants.
 */
@Component
@Slf4j
public class CalendarAggregateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("folder", Map.of(
                        "type", "string",
                        "description", "Suite folder containing _app.yaml "
                                + "(app: calendar) and the per-lane "
                                + "calendar files."));
                put("from", Map.of(
                        "type", "string",
                        "description", "ISO date (yyyy-MM-dd) — earliest "
                                + "occurrence. Default: 7 days before "
                                + "today."));
                put("to", Map.of(
                        "type", "string",
                        "description", "ISO date (yyyy-MM-dd) — latest "
                                + "occurrence. Default: 30 days after "
                                + "today."));
                put("lanes", Map.of(
                        "type", "array",
                        "items", Map.of("type", "string"),
                        "description", "Restrict to these lanes. Empty "
                                + "= all."));
                put("tags", Map.of(
                        "type", "array",
                        "items", Map.of("type", "string"),
                        "description", "Only events carrying at least "
                                + "one of these tags. Empty = all."));
                put("expandRecurring", Map.of(
                        "type", "boolean",
                        "description", "Expand RRULEs into concrete "
                                + "occurrences. Default true — when "
                                + "the user asks 'what's next week' "
                                + "they mean every standup, not the "
                                + "recurrence-rule head."));
                put("projectId", Map.of(
                        "type", "string",
                        "description", "Default: active project."));
            }},
            "required", List.of("folder"));

    private final EddieContext eddieContext;
    private final CalendarFolderReader folderReader;

    public CalendarAggregateTool(EddieContext eddieContext,
                                 CalendarFolderReader folderReader) {
        this.eddieContext = eddieContext;
        this.folderReader = folderReader;
    }

    @Override public String name() { return "calendar_aggregate"; }

    @Override
    public String description() {
        return "Read every kind:calendar file under a calendar-app "
                + "folder and return a flat, chronologically sorted "
                + "list of events. Recurrence rules are expanded "
                + "into the query window by default. Use this for "
                + "'what's coming up?', 'what's in lane X?', "
                + "'show me the milestones' questions. Does not "
                + "write any file.";
    }

    @Override public boolean primary() { return false; }

    @Override
    public Set<String> labels() {
        return Set.of("eddie", "read", "calendar");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String folder = paramString(params, "folder");
        if (folder == null) throw new ToolException("folder is required");

        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        String projectName = project.getName();

        CalendarFolderReader.Scan scan = folderReader.scan(
                ctx.tenantId(), projectName, folder);

        // Default window: last 7 days, next 30 days. Covers both
        // 'was hatte ich letzte Woche?' (small backward look) and
        // 'was kommt nächste Woche?' without the user spelling it
        // out.
        LocalDate today = LocalDate.now();
        LocalDate fromDate = parseDateOrDefault(paramString(params, "from"), today.minusDays(7));
        LocalDate toDate = parseDateOrDefault(paramString(params, "to"), today.plusDays(30));
        if (fromDate.isAfter(toDate)) {
            throw new ToolException("'from' (" + fromDate + ") is after 'to' (" + toDate + ").");
        }
        LocalDateTime rangeStart = fromDate.atStartOfDay();
        LocalDateTime rangeEnd = toDate.atTime(23, 59, 59);

        List<String> laneFilter = paramStringList(params, "lanes");
        List<String> tagFilter = paramStringList(params, "tags");
        boolean expandRecurring = paramBool(params, "expandRecurring", true);

        List<Map<String, Object>> output = new ArrayList<>();
        Map<String, Integer> perLaneCount = new TreeMap<>();

        for (CalendarFolderReader.CalendarFile cf : scan.calendars()) {
            if (!laneFilter.isEmpty() && !laneFilter.contains(cf.lane())) continue;
            for (CalendarEvent ev : cf.calendar().events()) {
                if (!matchesTags(ev, tagFilter)) continue;
                List<RecurrenceExpander.Occurrence> occs = expandRecurring
                        ? RecurrenceExpander.expand(ev, rangeStart, rangeEnd)
                        : pointOrSingle(ev, rangeStart, rangeEnd);
                for (RecurrenceExpander.Occurrence occ : occs) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("title", ev.title());
                    row.put("start", occ.startIso());
                    if (occ.endIso() != null) row.put("end", occ.endIso());
                    if (ev.allDay()) row.put("allDay", true);
                    row.put("lane", cf.lane());
                    row.put("sourcePath", cf.doc().getPath());
                    if (ev.location() != null) row.put("location", ev.location());
                    if (!ev.tags().isEmpty()) row.put("tags", List.copyOf(ev.tags()));
                    if (ev.recurrence() != null) row.put("recurrence", ev.recurrence());
                    output.add(row);
                    perLaneCount.merge(cf.lane(), 1, Integer::sum);
                }
            }
        }

        output.sort(Comparator.comparing(m -> (String) m.get("start")));

        List<Map<String, Object>> lanes = new ArrayList<>();
        for (Map.Entry<String, Integer> e : perLaneCount.entrySet()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", e.getKey());
            entry.put("eventCount", e.getValue());
            lanes.add(entry);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("folder", scan.folder());
        result.put("app", "calendar");
        result.put("window", Map.of("from", fromDate.toString(), "to", toDate.toString()));
        result.put("eventCount", output.size());
        result.put("lanes", lanes);
        result.put("events", output);

        log.info("CalendarAggregateTool tenant='{}' folder='{}' "
                        + "events={} window={}..{} lanes={}",
                ctx.tenantId(), scan.folder(), output.size(),
                fromDate, toDate, lanes.size());

        return result;
    }

    /** Single-event variant when recurrence expansion is off: emit
     *  the head occurrence if it falls in the window, else nothing. */
    private static List<RecurrenceExpander.Occurrence> pointOrSingle(
            CalendarEvent ev, LocalDateTime rangeStart, LocalDateTime rangeEnd) {
        RecurrenceExpander.ParsedRange anchor = RecurrenceExpander.parseEventRange(ev);
        if (anchor == null) return Collections.emptyList();
        if (anchor.start().isBefore(rangeStart) || anchor.start().isAfter(rangeEnd)) {
            return Collections.emptyList();
        }
        return List.of(new RecurrenceExpander.Occurrence(
                ev, anchor.start(), anchor.end(), anchor.allDay()));
    }

    private static boolean matchesTags(CalendarEvent ev, List<String> tagFilter) {
        if (tagFilter.isEmpty()) return true;
        for (String tag : tagFilter) {
            if (ev.tags().contains(tag)) return true;
        }
        return false;
    }

    private static LocalDate parseDateOrDefault(@Nullable String iso, LocalDate fallback) {
        if (iso == null || iso.isBlank()) return fallback;
        try {
            return LocalDate.parse(iso);
        } catch (java.time.format.DateTimeParseException e) {
            throw new ToolException(
                    "Could not parse date '" + iso + "' — expected ISO yyyy-MM-dd.");
        }
    }

    private static @Nullable String paramString(@Nullable Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    private static boolean paramBool(@Nullable Map<String, Object> params, String key, boolean fallback) {
        if (params == null) return fallback;
        Object v = params.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s);
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private static List<String> paramStringList(@Nullable Map<String, Object> params, String key) {
        if (params == null) return List.of();
        Object v = params.get(key);
        if (!(v instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof String s && !s.isBlank()) out.add(s.trim());
        }
        return out;
    }
}
