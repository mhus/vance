package de.mhus.vance.addon.brain.calendar;

import de.mhus.vance.shared.document.kind.KindCodecException;
import de.mhus.vance.shared.document.kind.KindHandler;
import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Registers the {@code timeline} document kind and carries its
 * semantic validation.
 *
 * <p>The codec is permissive by design — a malformed entry is dropped
 * so the other forty still render. That trade is only defensible if
 * something tells the author what disappeared, which is what
 * {@link #validate} is for. It reports three classes of problem the
 * renderer otherwise swallows: entries that were dropped, positions
 * that cannot be read against the declared axis, and orderings that
 * contradict themselves (a period ending before it starts, an
 * uncertainty window that does not contain the point it qualifies).
 */
@Service
public class TimelineKindHandler implements KindHandler {

    /** The canonical on-disk pair. Both keys, at the top level. */
    private static final Pattern AXIS_KEY = Pattern.compile("(?m)^axis:\\s*$");
    private static final Pattern ENTRIES_KEY = Pattern.compile("(?m)^entries:\\s*$");

    @Override
    public String getName() {
        return "timeline";
    }

    /**
     * Claims a body that carries both a top-level {@code axis:} and a
     * top-level {@code entries:} mapping. Narrow on purpose: either key
     * alone is plausible in half a dozen other kinds, the pair is not.
     */
    @Override
    public boolean detects(String content) {
        return AXIS_KEY.matcher(content).find() && ENTRIES_KEY.matcher(content).find();
    }

    /** Unmistakable marker pair — see {@link KindHandler#detectionPriority}. */
    @Override
    public int detectionPriority() {
        return 60;
    }

    @Override
    public List<Finding> validate(String content, KindValidationContext ctx) {
        List<Finding> findings = new ArrayList<>();
        String mime = effectiveMime(content, ctx.mimeType());
        if (mime == null) {
            findings.add(Finding.error("document", "timeline.mime",
                    "kind: timeline is YAML or JSON only — a markdown body cannot carry an "
                    + "axis declaration, lanes and per-entry uncertainty bounds. Save the "
                    + "document as .yaml (preferred) or .json."));
            return findings;
        }

        TimelineDocument doc;
        Map<String, Object> raw;
        try {
            doc = TimelineCodec.parse(content, mime);
            raw = TimelineCodec.rawBody(content, mime);
        } catch (KindCodecException e) {
            findings.add(Finding.error("document", "timeline.parse", e.getMessage()));
            return findings;
        }

        validateAxis(doc.axis(), raw, findings);
        validateDroppedEntries(raw, findings);
        validateEntries(doc, findings);
        return findings;
    }

    // ── Axis ───────────────────────────────────────────────────────

    private void validateAxis(TimelineAxis axis, Map<String, Object> raw, List<Finding> findings) {
        Object axisRaw = raw.get("axis");
        if (axisRaw instanceof Map<?, ?> map) {
            // Asked of the enum, not of the canonical word it resolved to:
            // `date` and `backwards` are documented spellings that parse
            // correctly, and comparing against modeWire()/directionWire()
            // reported exactly those as unknown.
            String declaredMode = string(map.get("mode"));
            if (declaredMode != null
                    && !TimelineAxis.TimelineAxisMode.isKnownWire(declaredMode)) {
                findings.add(Finding.warning("axis.mode", "timeline.axis.mode-unknown",
                        "axis.mode '" + declaredMode + "' is not a known mode — reading the "
                        + "document as '" + axis.modeWire() + "'. Valid values: "
                        + TimelineAxis.TimelineAxisMode.acceptedWires() + "."));
            }
            String declaredDirection = string(map.get("direction"));
            if (declaredDirection != null
                    && !TimelineAxis.TimelineDirection.isKnownWire(declaredDirection)) {
                findings.add(Finding.warning("axis.direction", "timeline.axis.direction-unknown",
                        "axis.direction '" + declaredDirection + "' is not a known direction — "
                        + "reading the axis as '" + axis.directionWire() + "'. Valid values: "
                        + TimelineAxis.TimelineDirection.acceptedWires() + "."));
            }
        }

        if (axis.mode() == TimelineAxis.TimelineAxisMode.DATETIME && axis.unit() != null) {
            findings.add(Finding.warning("axis.unit", "timeline.axis.unit-ignored",
                    "axis.unit is only used on a numeric axis; on a datetime axis the ruler "
                    + "labels itself from the dates. The value is kept but not rendered."));
        }

        Double from = TimelineScale.position(axis, axis.from());
        Double to = TimelineScale.position(axis, axis.to());
        if (axis.from() != null && from == null) {
            findings.add(Finding.warning("axis.from", "timeline.axis.bounds-unreadable",
                    unreadable("axis.from", axis.from(), axis)));
        }
        if (axis.to() != null && to == null) {
            findings.add(Finding.warning("axis.to", "timeline.axis.bounds-unreadable",
                    unreadable("axis.to", axis.to(), axis)));
        }
        if (from != null && to != null && from >= to) {
            findings.add(Finding.warning("axis", "timeline.axis.bounds-reversed",
                    "the axis window from '" + axis.from() + "' to '" + axis.to() + "' is empty "
                    + "or inverted"
                    + (axis.direction() == TimelineAxis.TimelineDirection.AGO
                            ? " — on an 'ago' axis the earlier bound is the LARGER number, "
                              + "so from must be greater than to."
                            : ".")
                    + " Fitting the view to the entries instead."));
        }
    }

    // ── Dropped entries ────────────────────────────────────────────

    /**
     * Reports entries the promotion threw away. The raw list is walked
     * by index so the message can point at a position in the file the
     * author can find — the promoted list no longer contains them, and
     * a bare count ("3 entries dropped") is not actionable.
     */
    private void validateDroppedEntries(Map<String, Object> raw, List<Finding> findings) {
        if (!(raw.get("entries") instanceof List<?> list)) {
            if (raw.containsKey("entries")) {
                findings.add(Finding.error("entries", "timeline.entries-not-a-list",
                        "entries must be a list of entry objects."));
            }
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            Object r = list.get(i);
            String at = "entries[" + i + "]";
            if (!(r instanceof Map<?, ?> map)) {
                findings.add(Finding.error(at, "timeline.entry.not-an-object",
                        "entry is not an object and was dropped."));
                continue;
            }
            String title = string(map.get("title"));
            String from = firstPresent(map, "from", "at", "start");
            if (title == null) {
                findings.add(Finding.error(at, "timeline.entry.title-missing",
                        "entry has no 'title' and was dropped."));
            }
            if (from == null) {
                findings.add(Finding.error(at, "timeline.entry.from-missing",
                        "entry" + (title != null ? " '" + title + "'" : "")
                        + " has no start position and was dropped. Give it 'from' "
                        + "(aliases 'at' / 'start' are also read)."));
            }
        }
    }

    // ── Entries ────────────────────────────────────────────────────

    private void validateEntries(TimelineDocument doc, List<Finding> findings) {
        TimelineAxis axis = doc.axis();
        Set<String> declaredLanes = new LinkedHashSet<>();
        for (TimelineLane lane : doc.lanes()) declaredLanes.add(lane.id());

        Map<String, TimelineEntry> byId = new LinkedHashMap<>();
        Set<String> duplicates = new LinkedHashSet<>();
        for (TimelineEntry en : doc.entries()) {
            if (byId.putIfAbsent(en.id(), en) != null) duplicates.add(en.id());
        }
        for (String dup : duplicates) {
            findings.add(Finding.error("entries", "timeline.entry.duplicate-id",
                    "id '" + dup + "' is used by more than one entry. Ids anchor 'parent' "
                    + "references and the reader's selection, so duplicates make both "
                    + "ambiguous."));
        }

        Set<String> reportedUndeclaredLanes = new HashSet<>();

        for (int i = 0; i < doc.entries().size(); i++) {
            TimelineEntry en = doc.entries().get(i);
            String at = "entries[" + i + "] '" + en.title() + "'";

            Double from = requirePosition(axis, en.from(), at, "from", findings);
            Double to = optionalPosition(axis, en.to(), at, "to", findings);
            Double fromEarliest =
                    optionalPosition(axis, en.fromEarliest(), at, "fromEarliest", findings);
            Double fromLatest =
                    optionalPosition(axis, en.fromLatest(), at, "fromLatest", findings);
            Double toEarliest =
                    optionalPosition(axis, en.toEarliest(), at, "toEarliest", findings);
            Double toLatest =
                    optionalPosition(axis, en.toLatest(), at, "toLatest", findings);

            if (from != null && to != null && to < from) {
                findings.add(Finding.error(at, "timeline.entry.reversed",
                        "the period ends before it starts: from '" + en.from() + "' to '"
                        + en.to() + "'"
                        + (axis.direction() == TimelineAxis.TimelineDirection.AGO
                                ? " — on an 'ago' axis the start is the LARGER number "
                                  + "(from: 201.4, to: 143.1)."
                                : ".")));
            }

            checkWindow(at, "from", en.from(), from, fromEarliest, fromLatest, findings);
            checkWindow(at, "to", en.to(), to, toEarliest, toLatest, findings);

            if (en.to() == null && (en.toEarliest() != null || en.toLatest() != null)) {
                findings.add(Finding.warning(at, "timeline.entry.end-bounds-without-end",
                        "toEarliest / toLatest bound an end this entry does not have. Either "
                        + "give it a 'to', or drop the bounds — a point in time is bounded by "
                        + "fromEarliest / fromLatest."));
            }

            if (en.parent() != null) {
                if (!byId.containsKey(en.parent())) {
                    findings.add(Finding.warning(at, "timeline.entry.parent-unknown",
                            "parent '" + en.parent() + "' is not the id of any entry in this "
                            + "document. The entry renders at the top nesting level."));
                } else if (en.parent().equals(en.id()) || hasCycle(en, byId)) {
                    findings.add(Finding.error(at, "timeline.entry.parent-cycle",
                            "the parent chain starting at '" + en.id() + "' is circular; "
                            + "nesting depth cannot be determined."));
                }
            }

            if (en.lane() != null
                    && !declaredLanes.isEmpty()
                    && !declaredLanes.contains(en.lane())
                    && reportedUndeclaredLanes.add(en.lane())) {
                findings.add(Finding.warning(at, "timeline.entry.lane-undeclared",
                        "lane '" + en.lane() + "' is not declared in 'lanes'. It renders after "
                        + "the declared lanes, in first-appearance order — declare it to "
                        + "control where it sits."));
            }
        }
    }

    private @Nullable Double requirePosition(
            TimelineAxis axis, String value, String at, String field, List<Finding> findings) {
        Double pos = TimelineScale.position(axis, value);
        if (pos == null) {
            findings.add(Finding.error(at, "timeline.entry.position-unreadable",
                    unreadable(field, value, axis)));
        }
        return pos;
    }

    private @Nullable Double optionalPosition(
            TimelineAxis axis, @Nullable String value, String at, String field,
            List<Finding> findings) {
        if (value == null) return null;
        Double pos = TimelineScale.position(axis, value);
        if (pos == null) {
            findings.add(Finding.error(at, "timeline.entry.position-unreadable",
                    unreadable(field, value, axis)));
        }
        return pos;
    }

    /**
     * An uncertainty window must contain the position it qualifies.
     * Not a formality: a window drawn beside its own point is worse
     * than no window at all — it renders a claim the author did not
     * make and cannot see is wrong.
     */
    private void checkWindow(
            String at, String field, @Nullable String rawPoint, @Nullable Double point,
            @Nullable Double earliest, @Nullable Double latest, List<Finding> findings) {
        if (earliest != null && latest != null && latest < earliest) {
            findings.add(Finding.error(at, "timeline.entry.uncertainty-window",
                    field + "Earliest is later than " + field + "Latest — the uncertainty "
                    + "window is inverted."));
            return;
        }
        if (point == null) return;
        if (earliest != null && point < earliest) {
            findings.add(Finding.error(at, "timeline.entry.uncertainty-window",
                    field + "Earliest lies after " + field + " ('" + rawPoint + "'). The window "
                    + "has to contain the position it qualifies."));
        }
        if (latest != null && point > latest) {
            findings.add(Finding.error(at, "timeline.entry.uncertainty-window",
                    field + "Latest lies before " + field + " ('" + rawPoint + "'). The window "
                    + "has to contain the position it qualifies."));
        }
    }

    private boolean hasCycle(TimelineEntry start, Map<String, TimelineEntry> byId) {
        Set<String> seen = new HashSet<>();
        TimelineEntry cursor = start;
        while (cursor != null && cursor.parent() != null) {
            if (!seen.add(cursor.id())) return true;
            cursor = byId.get(cursor.parent());
            if (cursor != null && cursor.id().equals(start.id())) return true;
        }
        return false;
    }

    // ── Helpers ────────────────────────────────────────────────────

    private static String unreadable(String field, String value, TimelineAxis axis) {
        return axis.mode() == TimelineAxis.TimelineAxisMode.DATETIME
                ? field + " '" + value + "' is not readable on a datetime axis. Expected an "
                  + "ISO-8601 value: '2026-03-04', '2026-03-04T21:40', "
                  + "'2026-03-04T21:40:00+01:00', or a bare year ('1969')."
                : field + " '" + value + "' is not a number. A numeric axis takes bare values "
                  + "like 201.4 — the unit lives in axis.unit, not in the position.";
    }

    /**
     * The mime type to parse with, or {@code null} when the body
     * cannot be a timeline at all. A missing type is sniffed: a body
     * starting with {@code {} is JSON, everything else is tried as
     * YAML (which is what an unsaved editor buffer looks like).
     */
    private static @Nullable String effectiveMime(String content, @Nullable String declared) {
        if (declared != null) {
            return TimelineCodec.supports(declared) ? declared : null;
        }
        return content.stripLeading().startsWith("{") ? "application/json" : "application/yaml";
    }

    private static @Nullable String string(@Nullable Object raw) {
        return ScalarCoercion.coerceToString(raw);
    }

    private static @Nullable String firstPresent(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            String v = string(map.get(key));
            if (v != null) return v;
        }
        return null;
    }
}
