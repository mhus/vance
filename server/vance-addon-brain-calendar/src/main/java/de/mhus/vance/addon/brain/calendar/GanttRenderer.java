package de.mhus.vance.addon.brain.calendar;

import de.mhus.vance.addon.brain.calendar.CalendarEvent;
import de.mhus.vance.addon.brain.calendar.CalendarsAppConfig;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * Generate a Mermaid {@code gantt} diagram source from a
 * {@link CalendarFolderReader.Scan}. Pure-function helper consumed by
 * {@code gantt_from_calendars}.
 *
 * <p>Sections are lanes (in the order given by
 * {@link CalendarsAppConfig.GanttConfig#sectionOrder()}, then
 * alphabetical fallback). Recurring events are intentionally
 * excluded unless {@code includeRecurring} is true in the config —
 * a Gantt with 60 daily-standup bars hides the milestones.
 *
 * <p>Tag → Mermaid status mapping:
 * <ul>
 *   <li>{@code criticalTags} → {@code :crit}</li>
 *   <li>{@code doneTags} → {@code :done}</li>
 *   <li>otherwise → no status modifier</li>
 * </ul>
 */
public final class GanttRenderer {

    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private GanttRenderer() { }

    /**
     * Render the calendar scan as a Mermaid-source string (full
     * Markdown body with the surrounding {@code ```mermaid} fence
     * suppressed — that wrapping happens at the caller so the
     * result can also be embedded into a {@code kind: diagram}
     * Markdown body or sent inline in chat).
     *
     * @param scan      the folder scan with all calendars + config.
     * @param fallbackTitle  used when the manifest has no title — typically
     *                       the suite folder name.
     * @param from      hard-clip lower bound (inclusive). {@code null} = no clip.
     * @param to        hard-clip upper bound (inclusive). {@code null} = no clip.
     */
    public static String render(CalendarFolderReader.Scan scan,
                                String fallbackTitle,
                                @Nullable LocalDate from,
                                @Nullable LocalDate to) {
        CalendarsAppConfig.GanttConfig cfg = scan.calendarConfig().gantt();
        String title = scan.manifest().title() != null
                ? scan.manifest().title() : fallbackTitle;

        Map<String, List<Task>> bySection = collectTasks(scan, cfg, from, to);

        StringBuilder out = new StringBuilder(256);
        out.append("gantt\n");
        out.append("    title ").append(safeTitle(title)).append('\n');
        out.append("    dateFormat YYYY-MM-DD\n");

        // Sort sections: explicit sectionOrder first (in the given
        // order), then everything else alphabetically. Empty sections
        // are dropped.
        List<String> orderedSections = orderSections(bySection.keySet(), cfg.sectionOrder());

        for (String section : orderedSections) {
            List<Task> tasks = bySection.get(section);
            if (tasks == null || tasks.isEmpty()) continue;
            CalendarsAppConfig.Lane lane = scan.calendarConfig().laneOrDefault(section);
            String label = lane.title() != null ? lane.title() : section;
            out.append("    section ").append(escapeText(label)).append('\n');
            for (Task t : tasks) {
                out.append("    ").append(t.renderLine()).append('\n');
            }
        }
        return out.toString();
    }

    // ── Task collection ───────────────────────────────────────────

    private record Task(
            String title,
            String taskId,
            @Nullable String status,
            LocalDate start,
            int durationDays) {

        String renderLine() {
            StringBuilder b = new StringBuilder();
            b.append(escapeText(title)).append(" :");
            if (status != null) b.append(status).append(", ");
            b.append(taskId).append(", ");
            b.append(ISO_DATE.format(start)).append(", ");
            b.append(durationDays).append('d');
            return b.toString();
        }
    }

    private static Map<String, List<Task>> collectTasks(
            CalendarFolderReader.Scan scan,
            CalendarsAppConfig.GanttConfig cfg,
            @Nullable LocalDate from,
            @Nullable LocalDate to) {

        Map<String, List<Task>> bySection = new TreeMap<>();
        int seq = 0;

        for (CalendarFolderReader.CalendarFile cf : scan.calendars()) {
            for (CalendarEvent ev : cf.calendar().events()) {
                if (ev.recurrence() != null && !ev.recurrence().isBlank()
                        && !cfg.includeRecurring()) {
                    continue;
                }
                if (!cfg.tagFilter().isEmpty() && !carriesAnyTag(ev, cfg.tagFilter())) {
                    continue;
                }
                Task task = buildTask(ev, ++seq, cfg, from, to);
                if (task == null) continue;
                bySection.computeIfAbsent(cf.lane(), k -> new ArrayList<>()).add(task);
            }
        }
        // Within each section, sort by start so the Gantt reads top-down.
        for (List<Task> list : bySection.values()) {
            list.sort(Comparator.comparing(Task::start));
        }
        return bySection;
    }

    private static @Nullable Task buildTask(CalendarEvent ev, int seq,
                                            CalendarsAppConfig.GanttConfig cfg,
                                            @Nullable LocalDate from,
                                            @Nullable LocalDate to) {
        RecurrenceExpander.ParsedRange range = RecurrenceExpander.parseEventRange(ev);
        if (range == null) return null;
        LocalDate startDate = range.start().toLocalDate();
        LocalDate endDate = range.end() != null ? range.end().toLocalDate() : startDate;

        if (from != null && endDate.isBefore(from)) return null;
        if (to != null && startDate.isAfter(to)) return null;

        int days = (int) Math.max(1, ChronoUnit.DAYS.between(startDate, endDate) + 1);
        String status = pickStatus(ev, cfg);
        String taskId = sanitiseId(ev.id(), seq);
        return new Task(ev.title(), taskId, status, startDate, days);
    }

    private static @Nullable String pickStatus(CalendarEvent ev,
                                               CalendarsAppConfig.GanttConfig cfg) {
        for (String t : cfg.doneTags()) {
            if (ev.tags().contains(t)) return "done";
        }
        for (String t : cfg.criticalTags()) {
            if (ev.tags().contains(t)) return "crit";
        }
        return null;
    }

    private static boolean carriesAnyTag(CalendarEvent ev, List<String> filter) {
        for (String t : filter) {
            if (ev.tags().contains(t)) return true;
        }
        return false;
    }

    // ── Mermaid escaping ──────────────────────────────────────────

    /** Strip characters Mermaid's gantt parser is allergic to — the
     *  colon is the field separator, the comma is the value separator,
     *  the hash starts a comment line. */
    static String escapeText(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.replace(':', '-')
                .replace(',', ' ')
                .replace('#', ' ')
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim();
    }

    /** Mermaid task titles in the {@code title} directive cannot
     *  contain certain chars either; the rules are looser but a
     *  conservative pass keeps it safe. */
    static String safeTitle(String s) {
        return escapeText(s);
    }

    /** Make a Mermaid task-id safe: only ASCII letters, digits and
     *  underscore. Mixed with a sequence number to guarantee
     *  uniqueness even when input ids are e.g. UUIDs that already
     *  collide after sanitisation. */
    static String sanitiseId(String raw, int seq) {
        StringBuilder sb = new StringBuilder();
        for (char c : raw.toCharArray()) {
            if (Character.isLetterOrDigit(c)) sb.append(c);
            else if (c == '_' || c == '-') sb.append('_');
        }
        if (sb.length() == 0 || !Character.isLetter(sb.charAt(0))) {
            sb.insert(0, 'e');
        }
        sb.append('_').append(seq);
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static List<String> orderSections(java.util.Set<String> available,
                                              List<String> explicitOrder) {
        List<String> remaining = new ArrayList<>(available);
        List<String> out = new ArrayList<>();
        for (String s : explicitOrder) {
            if (remaining.remove(s)) out.add(s);
        }
        remaining.sort(String::compareTo);
        out.addAll(remaining);
        return out;
    }

    /** Wrap the rendered source in a {@code kind: diagram} Markdown
     *  body with the {@code mermaid} fence — the same format that
     *  {@code DiagramCodec} produces. */
    public static String wrapAsDiagramMarkdown(String mermaidSource, String title) {
        StringBuilder out = new StringBuilder();
        out.append("---\n");
        out.append("kind: diagram\n");
        if (title != null && !title.isBlank()) {
            // Title goes only in core-meta — the gantt's own title
            // line is what the renderer shows. Don't try to dup-write
            // it into the body.
        }
        out.append("---\n\n");
        out.append("```mermaid\n");
        out.append(mermaidSource);
        if (!mermaidSource.endsWith("\n")) out.append('\n');
        out.append("```\n");
        return out.toString();
    }

    /** Coerce an arbitrary YAML/JSON scalar to a usable LocalDate.
     *  Used by tools that get window bounds from the manifest. */
    public static @Nullable LocalDate parseDate(@Nullable String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return LocalDate.parse(iso.trim());
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /** Default duration for events without an explicit end (zero-
     *  duration in the codec). Used by the renderer to pick a
     *  sensible bar width when the event is a milestone in disguise. */
    static int defaultDurationDays(LocalDateTime start, @Nullable LocalDateTime end) {
        if (end == null) return 1;
        long days = ChronoUnit.DAYS.between(start.toLocalDate(), end.toLocalDate()) + 1;
        return (int) Math.max(1, days);
    }
}
