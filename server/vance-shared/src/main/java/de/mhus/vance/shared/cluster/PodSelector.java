package de.mhus.vance.shared.cluster;

import de.mhus.vance.shared.project.ProjectDocument;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The one place that answers "may this project run on this pod".
 *
 * <p>Pure computation, no I/O — the caller already holds both documents.
 * Same shape and same hard rule as {@code ProjectOwnership}:
 * <b>{@code BrainPodDocument.labels}, {@code BrainPodDocument.exclusive} and
 * {@code ProjectDocument.placementSelector} are read here and nowhere else.</b>
 * A hand-rolled label comparison somewhere in the tree is how the twelve
 * divergent readings of {@code homeNode} came about.
 *
 * <h2>The rule</h2>
 * A pod is eligible when every entry of the project's selector is present on
 * the pod with the same value. An <b>empty selector matches every pod</b> —
 * not out of convenience but out of necessity: every project that exists today
 * has an empty one, and any other choice would be a migration touching all of
 * them.
 *
 * <p>{@link BrainPodDocument#isExclusive()} inverts that default for one pod:
 * there, an empty selector matches nothing. It is the counterpart to the rule
 * above and a pod-side property — the pod protects itself, the project does
 * not ask for protection. Together the two fields also express a full cordon
 * ({@code exclusive} plus no labels matches nothing at all), which is why
 * there is no third {@code cordoned} flag.
 *
 * <h2>Cost</h2>
 * An empty selector returns without looking at the pod's labels at all. That is
 * the acceptance condition for the most common state of the system — a
 * single-pod installation and every pre-existing project have an empty
 * selector, and placement there must measurably do what it did before labels
 * existed.
 *
 * <p>Design: {@code planning/project-placement-labels.md} §2.
 */
public final class PodSelector {

    /**
     * Label and selector keys become Mongo map keys, where a dot is read as a
     * path separator. Rejected rather than rewritten: silently turning
     * {@code eu.region} into {@code eu_region} would make a selector stop
     * matching a label the operator believes they set.
     */
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /** Values are never map keys, so dots and colons are allowed here. */
    private static final Pattern VALUE = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");

    private PodSelector() {}

    /**
     * Whether {@code pod} may run {@code project}. Capacity is deliberately not
     * part of this — eligibility and room are two questions, and telling them
     * apart is what lets the placement report distinguish "provide a pod of a
     * different kind" from "provide more of the same kind"
     * ({@code PlacementGap}).
     */
    public static boolean isEligible(ProjectDocument project, BrainPodDocument pod) {
        return matches(project.getPlacementSelector(), pod.getLabels(), pod.isExclusive());
    }

    /**
     * Raw form for callers that hold maps rather than documents.
     *
     * <p>Both maps are {@code @Nullable} on purpose: a document written before
     * these fields existed deserialises them as {@code null}, and "no labels"
     * and "an empty label map" are the same state. That is what makes this an
     * additive change with no migration behind it.
     */
    public static boolean matches(
            @Nullable Map<String, String> selector,
            @Nullable Map<String, String> podLabels,
            boolean exclusive) {
        if (selector == null || selector.isEmpty()) {
            return !exclusive;
        }
        if (podLabels == null || podLabels.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, String> required : selector.entrySet()) {
            if (!required.getValue().equals(podLabels.get(required.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Validates a label or selector map on the way in. Called by every write
     * path, so a map that reached persistence is known to be matchable.
     *
     * @throws InvalidLabelException on a key or value outside the grammar
     */
    public static void validate(@Nullable Map<String, String> labels) {
        if (labels == null) return;
        for (Map.Entry<String, String> entry : labels.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || !KEY.matcher(key).matches()) {
                throw new InvalidLabelException(
                        "Invalid label key '" + key + "' — expected 1-64 chars of "
                                + "[A-Za-z0-9_-] (dots are Mongo path separators)");
            }
            if (value == null || !VALUE.matcher(value).matches()) {
                throw new InvalidLabelException(
                        "Invalid value '" + value + "' for label '" + key
                                + "' — expected 1-128 chars of [A-Za-z0-9_.:-]");
            }
        }
    }

    /** A label key or value outside the grammar {@link #validate} enforces. */
    public static class InvalidLabelException extends IllegalArgumentException {
        public InvalidLabelException(String message) {
            super(message);
        }
    }
}
