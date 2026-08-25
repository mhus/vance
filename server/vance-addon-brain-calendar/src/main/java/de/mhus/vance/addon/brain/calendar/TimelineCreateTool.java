package de.mhus.vance.addon.brain.calendar;

import de.mhus.vance.api.progress.StatusTag;
import de.mhus.vance.brain.permission.SecurityContextFactory;
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
 * Create (or overwrite) a {@code kind: timeline} document from a
 * declared axis plus a typed list of entries.
 *
 * <p>Named tool rather than {@code doc_create_kind(kind="timeline")}
 * for the same reason {@code calendar_create} exists: a capability the
 * tool inventory does not advertise by name is a capability the model
 * substitutes with something from its training data. Without this tool
 * a request for "eine Zeitleiste der Erdzeitalter" reliably becomes a
 * Mermaid gantt (which cannot render negative years) or a markdown
 * table.
 *
 * <p>The axis is a required parameter and not inferred from the
 * entries. Inference would have to guess between "these look like
 * years" and "these are millions of years before present", and the two
 * differ only in the author's intent — a wrong guess draws the
 * Jurassic mirror-imaged with no error anywhere.
 */
@Component
@Slf4j
public class TimelineCreateTool implements Tool {

    private static final String YAML_MIME = "application/yaml";

    private static final Map<String, Object> AXIS_PROPS;
    static {
        AXIS_PROPS = new LinkedHashMap<>();
        AXIS_PROPS.put("mode", Map.of(
                "type", "string",
                "enum", List.of("numeric", "datetime"),
                "description", "'numeric' for a bare number line (deep time, "
                        + "hours-since-T0, page numbers of a story). 'datetime' for "
                        + "ISO-8601 dates and times. Required — one axis per document, "
                        + "never mixed."));
        AXIS_PROPS.put("unit", Map.of(
                "type", "string",
                "description", "Numeric axis only: unit suffix for tick labels. "
                        + "Free-form — 'Ma' (million years), 'ka', 'yr BP', 'min', "
                        + "'Tage'. Put the unit HERE, never into a position value."));
        AXIS_PROPS.put("direction", Map.of(
                "type", "string",
                "enum", List.of("forward", "ago"),
                "description", "Numeric axis only. 'forward' (default): larger "
                        + "number = later. 'ago': larger number = EARLIER — use for "
                        + "geological / archaeological scales ('201.4 Ma ago'). With "
                        + "'ago' a period runs from the larger to the smaller number."));
        AXIS_PROPS.put("from", Map.of(
                "type", "string",
                "description", "Optional left bound of the visible window. Omit to "
                        + "fit the entries."));
        AXIS_PROPS.put("to", Map.of(
                "type", "string",
                "description", "Optional right bound of the visible window."));
        AXIS_PROPS.put("label", Map.of(
                "type", "string",
                "description", "Optional caption under the ruler, e.g. 'Millionen "
                        + "Jahre vor heute'."));
    }

    private static final Map<String, Object> LANE_PROPS;
    static {
        LANE_PROPS = new LinkedHashMap<>();
        LANE_PROPS.put("id", Map.of(
                "type", "string",
                "description", "Lane id, referenced by entry.lane. Required."));
        LANE_PROPS.put("title", Map.of(
                "type", "string",
                "description", "Display label; defaults to the id."));
        LANE_PROPS.put("color", Map.of(
                "type", "string",
                "description", "Palette name or CSS colour for entries in this lane "
                        + "that declare none themselves."));
    }

    private static final Map<String, Object> ENTRY_PROPS;
    static {
        ENTRY_PROPS = new LinkedHashMap<>();
        ENTRY_PROPS.put("id", Map.of(
                "type", "string",
                "description", "Stable id. Needed only when another entry names this "
                        + "one as its 'parent'; otherwise auto-generated."));
        ENTRY_PROPS.put("title", Map.of(
                "type", "string",
                "description", "Display label. Required."));
        ENTRY_PROPS.put("from", Map.of(
                "type", "string",
                "description", "Start position, read against the axis. Numeric axis: "
                        + "a bare number ('201.4'). Datetime axis: ISO-8601 "
                        + "('2026-03-04T21:40', '2026-03-04', '1969'). Required."));
        ENTRY_PROPS.put("to", Map.of(
                "type", "string",
                "description", "End position. Present = a PERIOD (drawn as a bar), "
                        + "absent = a POINT (drawn as a marker). That is the only "
                        + "difference between the two — there is no separate event type."));
        ENTRY_PROPS.put("fromEarliest", Map.of(
                "type", "string",
                "description", "Earliest the start could be. Use for genuine "
                        + "uncertainty: 'last seen between 21:40 and 22:05' is "
                        + "from=21:40, fromLatest=22:05; '201.4 ± 0.2 Ma' is "
                        + "from=201.4, fromEarliest=201.6, fromLatest=201.2 on an "
                        + "'ago' axis. Do NOT hide uncertainty in notes — the drawing "
                        + "then shows a hard edge where there is none."));
        ENTRY_PROPS.put("fromLatest", Map.of(
                "type", "string",
                "description", "Latest the start could be."));
        ENTRY_PROPS.put("toEarliest", Map.of(
                "type", "string",
                "description", "Earliest the end could be. Only with 'to'."));
        ENTRY_PROPS.put("toLatest", Map.of(
                "type", "string",
                "description", "Latest the end could be. Only with 'to'."));
        ENTRY_PROPS.put("lane", Map.of(
                "type", "string",
                "description", "Lane id. Lanes are the parallel strands read against "
                        + "one clock — suspect / victim / witness, or stratigraphy / "
                        + "climate / fauna. Omit for the default lane."));
        ENTRY_PROPS.put("parent", Map.of(
                "type", "string",
                "description", "Id of the entry this one sits inside — era > period "
                        + "> epoch. Nesting is a flat list plus this reference, never "
                        + "nested objects."));
        ENTRY_PROPS.put("color", Map.of(
                "type", "string",
                "description", "Palette name (blue/green/red/orange/yellow/purple/"
                        + "pink/teal/gray) or CSS colour."));
        ENTRY_PROPS.put("tags", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "Free-form filter tags."));
        ENTRY_PROPS.put("notes", Map.of(
                "type", "string",
                "description", "Multi-line description — the evidence, the source, "
                        + "the reasoning."));
    }

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("axis", Map.of(
                        "type", "object",
                        "properties", AXIS_PROPS,
                        "required", List.of("mode"),
                        "description", "The axis declaration. Required — it is what "
                                + "makes the document renderable at all."));
                put("entries", Map.of(
                        "type", "array",
                        "items", Map.of(
                                "type", "object",
                                "properties", ENTRY_PROPS,
                                "required", List.of("title", "from")),
                        "description", "Periods and points, in any order."));
                put("lanes", Map.of(
                        "type", "array",
                        "items", Map.of(
                                "type", "object",
                                "properties", LANE_PROPS,
                                "required", List.of("id")),
                        "description", "Optional lane declarations, in render order. "
                                + "Declare a lane to fix its position or to show it "
                                + "while still empty ('no record of the witness that "
                                + "night'). Undeclared lanes named by entries are "
                                + "appended."));
                put("title", Map.of(
                        "type", "string",
                        "description", "Document title, also rendered above the ruler."));
                put("outputPath", Map.of(
                        "type", "string",
                        "description", "Storage path. Default: "
                                + "'timelines/<title-slug>-<timestamp>.yaml'."));
                put("projectId", Map.of(
                        "type", "string",
                        "description", "Optional project name; defaults to the active "
                                + "project."));
                put("overwrite", Map.of(
                        "type", "boolean",
                        "description", "When true and outputPath exists, replace the "
                                + "body instead of failing. Default false."));
            }},
            "required", List.of("axis", "entries"));

    private final EddieContext eddieContext;
    private final DocumentService documentService;
    private final DocumentLinkBuilder linkBuilder;
    private final ThinkProcessService thinkProcessService;
    private final ProgressEmitter progressEmitter;
    private final SecurityContextFactory contextFactory;

    public TimelineCreateTool(EddieContext eddieContext,
                              DocumentService documentService,
                              DocumentLinkBuilder linkBuilder,
                              ThinkProcessService thinkProcessService,
                              ProgressEmitter progressEmitter,
                              SecurityContextFactory contextFactory) {
        this.eddieContext = eddieContext;
        this.documentService = documentService;
        this.linkBuilder = linkBuilder;
        this.thinkProcessService = thinkProcessService;
        this.progressEmitter = progressEmitter;
        this.contextFactory = contextFactory;
    }

    @Override public String name() { return "timeline_create"; }

    @Override
    public String description() {
        return "Create a Vance kind:timeline document — periods and points on a "
                + "declared axis, in parallel lanes. Use for anything a calendar "
                + "cannot hold: geological / historical eras (numeric axis in "
                + "millions of years, counting backwards), the reconstruction of a "
                + "sequence of events at minute resolution across several actors, "
                + "project phases, a life story, the stages of a process. Periods "
                + "(from+to) and points (from only) live in the same list; entries "
                + "nest via 'parent' (era > period > epoch) and can carry genuine "
                + "uncertainty bounds. NOT a calendar (no appointments, no RRULE, no "
                + "ICS) and NOT a Gantt chart (no dependencies, no resources, no "
                + "progress).";
    }

    @Override public boolean primary() { return false; }

    @Override
    public Set<String> labels() {
        return Set.of("eddie", "write", "document", "timeline");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        List<Map<String, Object>> rawEntries = paramMapList(params, "entries");
        if (rawEntries == null || rawEntries.isEmpty()) {
            throw new ToolException(
                    "'entries' is empty — a timeline with no entries has no axis range "
                    + "and renders as a blank ruler. Collect the periods and points "
                    + "first, then make one call with all of them.");
        }

        TimelineAxis axis = buildAxis(paramMap(params, "axis"));
        List<TimelineLane> lanes = buildLanes(paramMapList(params, "lanes"));

        List<TimelineEntry> entries = new ArrayList<>(rawEntries.size());
        for (int i = 0; i < rawEntries.size(); i++) {
            entries.add(buildEntry(rawEntries.get(i), i, axis));
        }

        String title = paramString(params, "title");
        String outputPath = paramString(params, "outputPath");
        boolean overwrite = paramBoolean(params, "overwrite");

        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        String projectName = project.getName();
        ThinkProcessDocument process = loadProcess(ctx);

        String effectiveTitle = title != null ? title : "Timeline";
        String finalPath = outputPath != null ? outputPath : defaultOutputPath(effectiveTitle);

        TimelineDocument timeline = new TimelineDocument(
                "timeline", title, axis, lanes, entries, new LinkedHashMap<>());
        String yaml = TimelineCodec.serialize(timeline, YAML_MIME);
        byte[] bytes = yaml.getBytes(StandardCharsets.UTF_8);

        emit(process, StatusTag.INFO, String.format(Locale.ROOT,
                "Writing timeline with %d entries to '%s'…", entries.size(), finalPath));

        DocumentDocument stored;
        Optional<DocumentDocument> existing =
                documentService.findByPath(ctx.tenantId(), projectName, finalPath);
        if (existing.isPresent()) {
            if (!overwrite) {
                throw new ToolException(
                        "A document already exists at '" + finalPath + "'. Pass "
                        + "overwrite=true to replace it or pick a different outputPath.");
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
                    YAML_MIME,
                    DocumentService.TOOL_IDENTITY,
                    contextFactory.writeActor(
                            ctx.tenantId(), ctx.userId(), existing.get().getPath()));
        } else {
            try (InputStream in = new ByteArrayInputStream(bytes)) {
                stored = documentService.create(
                        ctx.tenantId(),
                        projectName,
                        finalPath,
                        effectiveTitle,
                        List.of("timeline"),
                        YAML_MIME,
                        in,
                        ctx.userId(),
                        contextFactory.writeActor(ctx.tenantId(), ctx.userId(), finalPath));
            } catch (IOException e) {
                throw new ToolException("Could not store timeline: " + e.getMessage());
            }
        }

        String vanceUri = DocumentLinkBuilder.buildVanceUri(
                null, stored.getPath(), "timeline",
                DocumentLinkBuilder.defaultModeForKind("timeline"));
        String markdownLink = linkBuilder.linkFor(stored, projectName);

        log.info("TimelineCreateTool tenant='{}' entries={} axis={} path='{}' replaced={}",
                ctx.tenantId(), entries.size(), axis.modeWire(), finalPath, existing.isPresent());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", stored.getPath());
        out.put("entryCount", entries.size());
        out.put("periodCount", entries.stream().filter(TimelineEntry::isPeriod).count());
        out.put("laneCount", lanes.size());
        out.put("size", stored.getSize());
        out.put("vanceUri", vanceUri);
        out.put("markdownLink", markdownLink);
        if (existing.isPresent()) out.put("replaced", true);
        return out;
    }

    // ── Building ──────────────────────────────────────────────────

    private static TimelineAxis buildAxis(@Nullable Map<String, Object> raw) {
        if (raw == null) {
            throw new ToolException(
                    "'axis' is required. Declare mode='numeric' (with unit, e.g. 'Ma', "
                    + "and direction='ago' for scales counting backwards from today) or "
                    + "mode='datetime' for ISO-8601 dates and times.");
        }
        String modeRaw = stringOrNull(raw.get("mode"));
        if (modeRaw == null) {
            throw new ToolException(
                    "'axis.mode' is required — 'numeric' or 'datetime'. It is not inferred "
                    + "from the entries: '201.4' is a plausible year and a plausible "
                    + "'millions of years ago', and guessing wrong draws the timeline "
                    + "mirror-imaged with no error anywhere.");
        }
        TimelineAxis.TimelineAxisMode mode = TimelineAxis.TimelineAxisMode.fromWire(modeRaw);
        if (mode == TimelineAxis.TimelineAxisMode.NUMERIC
                && !"numeric".equalsIgnoreCase(modeRaw.trim())) {
            throw new ToolException(
                    "'axis.mode' must be 'numeric' or 'datetime', got '" + modeRaw + "'.");
        }
        return new TimelineAxis(
                mode,
                stringOrNull(raw.get("unit")),
                TimelineAxis.TimelineDirection.fromWire(stringOrNull(raw.get("direction"))),
                stringOrNull(raw.get("from")),
                stringOrNull(raw.get("to")),
                stringOrNull(raw.get("label")),
                new LinkedHashMap<>());
    }

    private static List<TimelineLane> buildLanes(@Nullable List<Map<String, Object>> raw) {
        List<TimelineLane> out = new ArrayList<>();
        if (raw == null) return out;
        for (Map<String, Object> lane : raw) {
            String id = stringOrNull(lane.get("id"));
            if (id == null) {
                throw new ToolException("every lane needs an 'id' — it is what entry.lane "
                        + "references.");
            }
            out.add(new TimelineLane(
                    id, stringOrNull(lane.get("title")), stringOrNull(lane.get("color"))));
        }
        return out;
    }

    private static TimelineEntry buildEntry(
            Map<String, Object> raw, int index, TimelineAxis axis) {
        String title = stringOrNull(raw.get("title"));
        if (title == null) {
            throw new ToolException(
                    "entries[" + index + "] is missing 'title' — required for every entry.");
        }
        String from = firstOf(raw, "from", "at", "start");
        if (from == null) {
            throw new ToolException(
                    "entries[" + index + "] ('" + title + "') is missing 'from' — every "
                    + "entry needs a start position on the axis.");
        }
        requireReadable(axis, from, "from", index, title);
        String to = firstOf(raw, "to", "end", "until");
        requireReadable(axis, to, "to", index, title);

        String idStr = stringOrNull(raw.get("id"));
        String id = idStr != null ? idStr : UUID.randomUUID().toString();

        return new TimelineEntry(
                id,
                title,
                from,
                to,
                stringOrNull(raw.get("fromEarliest")),
                stringOrNull(raw.get("fromLatest")),
                stringOrNull(raw.get("toEarliest")),
                stringOrNull(raw.get("toLatest")),
                stringOrNull(raw.get("lane")),
                stringOrNull(raw.get("parent")),
                stringOrNull(raw.get("color")),
                stringList(raw.get("tags")),
                stringOrNull(raw.get("notes")),
                new LinkedHashMap<>());
    }

    /**
     * Rejects a position the declared axis cannot read. Fails the call
     * rather than storing it: the codec would keep the string and the
     * renderer would skip the entry, so the model would be told the
     * timeline was written while the reader sees a gap.
     */
    private static void requireReadable(
            TimelineAxis axis, @Nullable String value, String field, int index, String title) {
        if (value == null) return;
        if (TimelineScale.position(axis, value) != null) return;
        throw new ToolException("entries[" + index + "] ('" + title + "'): " + field + " '"
                + value + "' cannot be read on a "
                + axis.modeWire() + " axis. "
                + (axis.mode() == TimelineAxis.TimelineAxisMode.DATETIME
                        ? "Expected ISO-8601 — '2026-03-04T21:40', '2026-03-04' or a bare "
                          + "year like '1969'."
                        : "Expected a bare number like '201.4'; the unit belongs in "
                          + "axis.unit, not in the value."));
    }

    // ── Path helpers ──────────────────────────────────────────────

    static String defaultOutputPath(@Nullable String title) {
        String stamp = DateTimeFormatter
                .ofPattern("yyyy-MM-dd-HHmmss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        String slug = (title == null || title.isBlank())
                ? "timeline" : IcsToCalendarTool.slug(title);
        return "timelines/" + slug + "-" + stamp + ".yaml";
    }

    // ── Helpers ───────────────────────────────────────────────────

    private @Nullable ThinkProcessDocument loadProcess(ToolInvocationContext ctx) {
        if (ctx == null || ctx.processId() == null) return null;
        return thinkProcessService.findById(ctx.processId()).orElse(null);
    }

    private void emit(@Nullable ThinkProcessDocument process, StatusTag tag, String text) {
        if (process == null) return;
        progressEmitter.emitStatus(process, tag, text);
    }

    private static @Nullable String firstOf(Map<String, Object> raw, String... keys) {
        for (String key : keys) {
            String v = stringOrNull(raw.get(key));
            if (v != null) return v;
        }
        return null;
    }

    private static @Nullable String stringOrNull(@Nullable Object v) {
        if (v instanceof String s) return s.isBlank() ? null : s.trim();
        if (v != null) {
            String s = v.toString();
            return s.isBlank() ? null : s;
        }
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

    private static @Nullable String paramString(@Nullable Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    private static boolean paramBoolean(@Nullable Map<String, Object> params, String key) {
        if (params == null) return false;
        return params.get(key) instanceof Boolean b && b;
    }

    private static @Nullable Map<String, Object> paramMap(
            @Nullable Map<String, Object> params, String key) {
        if (params == null) return null;
        if (!(params.get(key) instanceof Map<?, ?> m)) return null;
        return coerceMap(m);
    }

    private static @Nullable List<Map<String, Object>> paramMapList(
            @Nullable Map<String, Object> params, String key) {
        if (params == null) return null;
        if (!(params.get(key) instanceof List<?> list)) return null;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) out.add(coerceMap(m));
        }
        return out;
    }

    private static Map<String, Object> coerceMap(Map<?, ?> m) {
        Map<String, Object> coerced = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (e.getKey() != null) coerced.put(e.getKey().toString(), e.getValue());
        }
        return coerced;
    }
}
