package de.mhus.vance.addon.brain.calendar;

import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Bootstrap a new calendar application — writes the
 * {@code _app.yaml} manifest with the correct schema and returns
 * lane descriptors with {@code suggestedFilePath} so the LLM
 * doesn't have to invent the sub-folder convention.
 *
 * <p>This is the **first** call the LLM should make when the user
 * asks for a multi-lane planning app / Sprint plan / Gantt-style
 * project layout. Hand-writing {@code _app.yaml} via
 * {@code doc_create_kind} or {@code doc_create_text} is brittle —
 * the model typically gets one of {kind, app, lane structure} wrong
 * and the resulting app silently misbehaves.
 */
@Component
@Slf4j
public class CalendarAppCreateTool implements Tool {

    private static final Map<String, Object> LANE_ITEM_SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("name", Map.of(
                        "type", "string",
                        "description", "Lane id — short, "
                                + "filesystem-safe (lowercase, "
                                + "alphanumeric, dashes). Becomes "
                                + "the sub-folder name."));
                put("title", Map.of(
                        "type", "string",
                        "description", "Display label. Defaults to "
                                + "the lane name."));
                put("color", Map.of(
                        "type", "string",
                        "description", "Palette name (blue/green/red/"
                                + "orange/yellow/purple/pink/teal/"
                                + "gray) or any CSS color."));
                put("order", Map.of(
                        "type", "integer",
                        "description", "Sort position in Gantt. "
                                + "Auto-assigned when missing."));
            }},
            "required", List.of("name"));

    private static final Map<String, Object> EVENT_ITEM_SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("title", Map.of("type", "string",
                        "description", "Event title. Required."));
                put("start", Map.of("type", "string",
                        "description", "ISO-8601 date or date-time. Required. "
                                + "All-day: '2026-06-12'. Timed: '2026-06-12T09:00' "
                                + "or with offset '2026-06-12T09:00:00+02:00'."));
                put("end", Map.of("type", "string",
                        "description", "ISO-8601 end. Same format as start."));
                put("allDay", Map.of("type", "boolean",
                        "description", "True for full-day events. "
                                + "start/end must be date-only strings then."));
                put("lane", Map.of("type", "string",
                        "description", "Lane name this event belongs to "
                                + "(e.g. 'design', 'backend'). Auto-creates "
                                + "the lane if it's not in `lanes`. Default "
                                + "lane is 'common' (for cross-team events "
                                + "like Sprint Planning, Standups, Reviews)."));
                put("recurrence", Map.of("type", "string",
                        "description", "RFC 5545 RRULE, e.g. "
                                + "'FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR;UNTIL=20260626T000000Z'."));
                put("location", Map.of("type", "string"));
                put("attendees", Map.of("type", "array",
                        "items", Map.of("type", "string")));
                put("tags", Map.of("type", "array",
                        "items", Map.of("type", "string"),
                        "description", "Free-form tags. `milestone`/`critical` "
                                + "make events stand out in the Gantt."));
                put("color", Map.of("type", "string"));
                put("notes", Map.of("type", "string"));
            }},
            "required", List.of("title", "start"));

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("folder", Map.of(
                        "type", "string",
                        "description", "Folder for the new calendar "
                                + "app. Created as needed. The "
                                + "manifest will live at "
                                + "<folder>/_app.yaml."));
                put("title", Map.of(
                        "type", "string",
                        "description", "Display title for the app "
                                + "(shown in Gantt header etc.)."));
                put("description", Map.of(
                        "type", "string",
                        "description", "Optional description."));
                put("lanes", Map.of(
                        "type", "array",
                        "items", LANE_ITEM_SCHEMA,
                        "description", "Lanes for the planning suite. "
                                + "Each lane becomes a sub-folder. "
                                + "Order in this list = render order "
                                + "in the Gantt (overridable per "
                                + "lane via `order`). Lanes referenced "
                                + "by events but not listed here are "
                                + "auto-added with defaults. "
                                + "ACCEPTS SHORTHAND: each entry can "
                                + "be either an object "
                                + "({name, title?, color?, order?}) "
                                + "OR just the lane-name as a string "
                                + "— e.g. lanes=['design','backend'] "
                                + "is equivalent to "
                                + "lanes=[{name:'design'},{name:'backend'}]."));
                put("events", Map.of(
                        "type", "array",
                        "items", EVENT_ITEM_SCHEMA,
                        "description", "ONE-SHOT FORM. When you pass "
                                + "events here, the tool writes the "
                                + "manifest, dispatches events to "
                                + "their lanes' source files, AND "
                                + "auto-runs app_rebuild — all in "
                                + "this single call. The result's "
                                + "`artefacts` array carries the "
                                + "Gantt + Conflicts paths to embed "
                                + "in chat. Use this for full sprint-"
                                + "plan setup in one tool call."));
                put("window", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "from", Map.of("type", "string",
                                        "description", "ISO date, e.g. 2026-06-01."),
                                "until", Map.of("type", "string",
                                        "description", "ISO date.")),
                        "description", "Optional date window for the "
                                + "Gantt rendering."));
                put("overwrite", Map.of(
                        "type", "boolean",
                        "description", "Allow replacing an existing "
                                + "_app.yaml at this path. Default "
                                + "false — fails if the file exists."));
                put("projectId", Map.of(
                        "type", "string",
                        "description", "Default: active project."));
            }},
            "required", List.of("folder"));

    private final EddieContext eddieContext;
    private final CalendarsApplication calendarsApplication;

    public CalendarAppCreateTool(EddieContext eddieContext,
                                 CalendarsApplication calendarsApplication) {
        this.eddieContext = eddieContext;
        this.calendarsApplication = calendarsApplication;
    }

    @Override public String name() { return "calendar_app_create"; }

    @Override
    public String description() {
        return "ONE-SHOT bootstrap for a calendar-suite. Pass `folder` "
                + "+ `lanes` + `events` (each with `lane:` hint), and "
                + "this tool writes the manifest, distributes events "
                + "to per-lane files, and auto-rebuilds the Gantt + "
                + "Conflicts artifacts in a single call. ALWAYS use "
                + "this for sprint plans / multi-lane planning apps "
                + "/ project Gantts. Do NOT hand-write _app.yaml via "
                + "doc_create_kind/doc_create_text, do NOT chain "
                + "multiple calendar_create + app_rebuild calls when "
                + "events are known up-front — pass them here instead.";
    }

    @Override public boolean primary() { return false; }

    @Override
    public Set<String> labels() {
        return Set.of("eddie", "write", "document", "calendar", "application");
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

        Map<String, Object> createParams = new LinkedHashMap<>();
        copyIfPresent(params, createParams, "title");
        copyIfPresent(params, createParams, "description");
        copyIfPresent(params, createParams, "lanes");
        copyIfPresent(params, createParams, "window");
        copyIfPresent(params, createParams, "events");

        VanceApplication.CreateContext cc = new VanceApplication.CreateContext(
                ctx.tenantId(), project.getName(), normaliseFolder(folder),
                ctx.userId(), ctx.processId(),
                paramBoolean(params, "overwrite"),
                createParams);

        VanceApplication.CreateResult result = calendarsApplication.create(cc);

        log.info("CalendarAppCreateTool tenant='{}' folder='{}' "
                        + "lanes={} manifestPath='{}'",
                ctx.tenantId(), folder, result.lanes().size(),
                result.manifestPath());

        return result.toMap();
    }

    // ── Helpers ───────────────────────────────────────────────────

    private static String normaliseFolder(String folder) {
        String f = folder.trim();
        while (f.endsWith("/")) f = f.substring(0, f.length() - 1);
        while (f.startsWith("/")) f = f.substring(1);
        if (f.isEmpty()) throw new ToolException("folder must not be empty");
        return f;
    }

    private static @Nullable String paramString(@Nullable Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    private static boolean paramBoolean(@Nullable Map<String, Object> params, String key) {
        if (params == null) return false;
        Object v = params.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s);
        return false;
    }

    private static void copyIfPresent(Map<String, Object> src, Map<String, Object> dst, String key) {
        Object v = src == null ? null : src.get(key);
        if (v != null) dst.put(key, v);
    }
}
