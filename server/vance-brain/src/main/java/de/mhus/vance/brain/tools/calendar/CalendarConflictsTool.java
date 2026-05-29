package de.mhus.vance.brain.tools.calendar;

import de.mhus.vance.brain.applications.CalendarsApplication;
import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Regenerate only the {@code _conflicts.yaml} artefact for a
 * calendar-app folder. Thin wrapper around
 * {@link CalendarsApplication#refreshConflicts}.
 *
 * <p>For the common "refresh everything" case use {@code app_rebuild}
 * instead — it touches both the Gantt and the conflicts atomically.
 */
@Component
@Slf4j
public class CalendarConflictsTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("folder", Map.of(
                        "type", "string",
                        "description", "Calendar-app folder containing "
                                + "_app.yaml (app: calendar)."));
                put("from", Map.of(
                        "type", "string",
                        "description", "Optional ISO date — earliest "
                                + "moment to check. Default: today."));
                put("to", Map.of(
                        "type", "string",
                        "description", "Optional ISO date — latest "
                                + "moment to check. Default: 180 days "
                                + "from today (or _app.yaml's "
                                + "calendar.window.until)."));
                put("projectId", Map.of(
                        "type", "string",
                        "description", "Default: active project."));
            }},
            "required", List.of("folder"));

    private final EddieContext eddieContext;
    private final CalendarsApplication calendarsApplication;

    public CalendarConflictsTool(EddieContext eddieContext,
                                 CalendarsApplication calendarsApplication) {
        this.eddieContext = eddieContext;
        this.calendarsApplication = calendarsApplication;
    }

    @Override public String name() { return "calendar_conflicts"; }

    @Override
    public String description() {
        return "Regenerate _conflicts.yaml under a calendar-app "
                + "folder. Detects time-overlap conflicts between "
                + "every pair of events across all lanes, expands "
                + "recurrences, honours ignoreWithinTags from "
                + "_app.yaml. Use app_rebuild instead when you also "
                + "want the Gantt refreshed.";
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
        String folder = paramString(params, "folder");
        if (folder == null) throw new ToolException("folder is required");

        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        LocalDate from = parseDate(paramString(params, "from"));
        LocalDate to = parseDate(paramString(params, "to"));

        VanceApplication.RefreshContext rc = new VanceApplication.RefreshContext(
                ctx.tenantId(), project.getName(), folder,
                ctx.userId(), ctx.processId());

        VanceApplication.ArtefactResult result =
                calendarsApplication.refreshConflicts(rc, from, to);

        log.info("CalendarConflictsTool tenant='{}' folder='{}' -> {}",
                ctx.tenantId(), folder, result.path());

        Map<String, Object> out = new LinkedHashMap<>(result.toMap());
        out.put("folder", folder);
        return out;
    }

    private static @Nullable LocalDate parseDate(@Nullable String iso) {
        if (iso == null || iso.isBlank()) return null;
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
}
