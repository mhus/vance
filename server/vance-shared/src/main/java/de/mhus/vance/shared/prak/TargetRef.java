package de.mhus.vance.shared.prak;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Reference to one or more existing memory entries that an
 * {@link AffectsExisting} action operates on.
 *
 * <p>Which fields are populated depends on {@link #kind()}:
 * <ul>
 *   <li>{@link TargetRefKind#MEMORY_ID}: {@link #value()} is the memory id.</li>
 *   <li>{@link TargetRefKind#LABELS}: {@link #labels()} holds the
 *       label set, {@link #matchMode()} and {@link #minOverlap()}
 *       control the candidate query.</li>
 *   <li>{@link TargetRefKind#PATTERN}: {@link #value()} is the
 *       regex / keyword pattern.</li>
 * </ul>
 *
 * <p>The label-lookup pipeline (see {@code memory-evaluation-pipeline.md}
 * §7) resolves candidates via Mongo query plus an LLM judge, so
 * {@code minOverlap} of 2 is the sensible default — singleton labels
 * like {@code general} would otherwise match everything.
 */
public record TargetRef(
        TargetRefKind kind,
        @Nullable String value,
        List<String> labels,
        @Nullable String matchMode,
        int minOverlap) {

    /** Default match-mode for label lookup: "intersect-then-judge". */
    public static final String MATCH_MODE_INTERSECT_THEN_JUDGE = "intersect-then-judge";

    public static TargetRef byMemoryId(String memoryId) {
        return new TargetRef(TargetRefKind.MEMORY_ID, memoryId, List.of(), null, 0);
    }

    public static TargetRef byLabels(List<String> labels, int minOverlap) {
        return new TargetRef(
                TargetRefKind.LABELS, null, List.copyOf(labels),
                MATCH_MODE_INTERSECT_THEN_JUDGE, minOverlap);
    }

    public static TargetRef byPattern(String pattern) {
        return new TargetRef(TargetRefKind.PATTERN, pattern, List.of(), null, 0);
    }
}
