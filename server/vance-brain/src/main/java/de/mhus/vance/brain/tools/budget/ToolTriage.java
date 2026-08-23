package de.mhus.vance.brain.tools.budget;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure triage of an over-sized tool surface: demotes whole tool families
 * from the per-turn manifest until the remaining set fits the provider's
 * {@code tools}-array cap. Nothing is lost — a demoted tool stays in the
 * deferred bucket and keeps its discovery-block line, so the model can
 * still reach it (one round-trip later).
 *
 * <p><b>Two axes, deliberately not mixed.</b> The <em>declared class</em>
 * (mandatory → activated → recipe-keep → recipe-add → built-in → pack →
 * recipe-drop-first) is the coarse order. The <em>measured signals</em>
 * (activation recency, call counts) only break ties <em>inside</em> one
 * class. Sorting purely by usage would let a popular convenience tool
 * displace a rarely-used but necessary one.
 *
 * <p><b>Families, not single tools.</b> See {@link ToolFamily}. An
 * explicit hint carves its tools out of the family: naming
 * {@code doc_note_add} in {@code allowedToolsDropFirst} splits it off
 * from the rest of {@code doc_*} rather than dragging the whole family
 * down — which is what an author naming a single tool means. A hint may
 * also be a trailing-{@code *} prefix ({@code doc_*}) to rank a whole
 * group without listing its members.
 *
 * <p><b>Stable order.</b> Ranking decides <em>membership</em> only; the
 * emitted {@code tools} array stays alphabetically sorted in
 * {@code ContextToolsApi.visibleResolved()} because that ordering is what
 * makes the prompt-cache prefix stable. Two turns with the same inputs
 * therefore produce the same manifest, byte for byte.
 */
public final class ToolTriage {

    // Priority classes. Lower survives longer. MANDATORY is not a class
    // here — the floor never enters the demotion pool at all.
    private static final int TIER_ACTIVATED = 1;
    private static final int TIER_KEEP = 2;
    private static final int TIER_ADD = 3;
    private static final int TIER_BUILTIN = 4;
    private static final int TIER_PACK = 5;
    private static final int TIER_DROP_FIRST = 6;

    private ToolTriage() {}

    /**
     * Declared priority hints for this turn. The per-name sets come from
     * the recipe's mode/profile cascade (see
     * {@code RecipeResolver.ToolFilter}); the per-family sets come from
     * {@code vance.tools.budget.*} and exist for the cases where the
     * name-derived order is demonstrably wrong for a deployment.
     *
     * <p>A name hint beats a family hint — the more specific statement
     * wins.
     *
     * @param keep              {@code allowedToolsKeep} — hold in the
     *                          manifest under budget pressure ("important")
     * @param add               {@code allowedToolsAdd} — explicit
     *                          promotion; ranks just below {@code keep}
     *                          because the author asked for it by name
     * @param dropFirst         {@code allowedToolsDropFirst} — give these
     *                          up before anything else ("less important")
     * @param keepFamilies      families ranked as "important"
     * @param dropFirstFamilies families ranked as "less important"
     */
    public record Hints(
            Set<String> keep,
            Set<String> add,
            Set<String> dropFirst,
            Set<String> keepFamilies,
            Set<String> dropFirstFamilies) {

        public static final Hints EMPTY =
                new Hints(Set.of(), Set.of(), Set.of(), Set.of(), Set.of());

        public Hints {
            keep = keep == null ? Set.of() : Set.copyOf(keep);
            add = add == null ? Set.of() : Set.copyOf(add);
            dropFirst = dropFirst == null ? Set.of() : Set.copyOf(dropFirst);
            keepFamilies = keepFamilies == null ? Set.of() : Set.copyOf(keepFamilies);
            dropFirstFamilies = dropFirstFamilies == null
                    ? Set.of() : Set.copyOf(dropFirstFamilies);
        }

        /** Per-name hints only — no family-level override configured. */
        public static Hints ofNames(Set<String> keep, Set<String> add, Set<String> dropFirst) {
            return new Hints(keep, add, dropFirst, Set.of(), Set.of());
        }

        public boolean isEmpty() {
            return keep.isEmpty() && add.isEmpty() && dropFirst.isEmpty()
                    && keepFamilies.isEmpty() && dropFirstFamilies.isEmpty();
        }

        /**
         * Does {@code name} match the keep list — exact name or a
         * trailing-{@code *} prefix pattern ({@code doc_*})?
         */
        public boolean matchesKeep(String name) {
            return matches(keep, name);
        }

        /** Same as {@link #matchesKeep} for the drop-first list. */
        public boolean matchesDropFirst(String name) {
            return matches(dropFirst, name);
        }

        /**
         * Exact membership first, then trailing-{@code *} prefixes. The
         * pattern form exists so a recipe can rank a whole family without
         * spelling out forty tool names; {@code *} cannot occur in a tool
         * name, so the two forms never collide.
         */
        private static boolean matches(Set<String> entries, String name) {
            if (entries.isEmpty()) return false;
            if (entries.contains(name)) return true;
            for (String entry : entries) {
                if (entry.length() > 1 && entry.endsWith("*")
                        && name.startsWith(entry.substring(0, entry.length() - 1))) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Outcome of a triage run.
     *
     * @param primary         kept primary names (mandatory always in)
     * @param activated       kept activated-deferred names
     * @param demoted         names removed from the surface, in demotion
     *                        order — least important first, so the list
     *                        reads as "this went first, then this". For
     *                        the log line, not for the model
     * @param demotedFamilies distinct families the demotion touched, in the
     *                        same order as {@code demoted}
     * @param limit           the effective limit that was applied
     */
    public record Result(
            Set<String> primary,
            Set<String> activated,
            List<String> demoted,
            List<String> demotedFamilies,
            int limit) {

        public boolean changed() {
            return !demoted.isEmpty();
        }
    }

    /**
     * Fit {@code primary ∪ activated} into {@code budget}.
     *
     * @param primary   classified primary set (includes the mandatory floor)
     * @param activated activated deferred tools — they ship in the
     *                  manifest too, so they count against the cap
     * @param mandatory floor names that must never be demoted
     * @param hints     declared priority from the recipe
     * @param budget    limit + measured signals
     * @return the kept sets plus what was given up; {@link Result#changed()}
     *         is false when the surface already fitted
     * @throws ToolBudgetException when the limit cannot even hold the floor
     */
    public static Result apply(
            Set<String> primary,
            Set<String> activated,
            Set<String> mandatory,
            Hints hints,
            ToolBudget budget) {
        Set<String> primaryIn = primary == null ? Set.of() : primary;
        Set<String> activatedIn = activated == null ? Set.of() : activated;
        Set<String> floor = mandatory == null ? Set.of() : mandatory;
        Hints h = hints == null ? Hints.EMPTY : hints;

        // Union, not sum: the two sets are disjoint straight out of
        // classify(), but a post-classification widening (withAdditional
        // promoting a tool the model had already activated) can put a name
        // in both. Counting it twice makes the surface look a slot larger
        // than it is — and at the boundary that costs a whole family.
        int surfaceSize = primaryIn.size();
        for (String name : activatedIn) {
            if (!primaryIn.contains(name)) surfaceSize++;
        }
        if (budget == null || !budget.hasLimit() || surfaceSize <= budget.effectiveLimit()) {
            return new Result(primaryIn, activatedIn, List.of(), List.of(),
                    budget == null ? 0 : budget.effectiveLimit());
        }

        int limit = budget.effectiveLimit();
        // The floor is not negotiable — a cap that cannot hold it is a
        // configuration error, not something to work around silently.
        Set<String> keptFloor = new LinkedHashSet<>();
        for (String name : primaryIn) {
            if (floor.contains(name)) keptFloor.add(name);
        }
        if (keptFloor.size() > limit) {
            throw new ToolBudgetException(
                    "Tool budget too small: maxTools=" + budget.maxTools()
                            + " minus reserved=" + budget.reserved()
                            + " leaves " + limit + " slot(s), but the mandatory floor needs "
                            + keptFloor.size() + " (" + String.join(", ", keptFloor) + ")");
        }

        // Activations beyond the cap lose their top-class standing and
        // compete in the lowest one instead of being dropped outright:
        // if there is room they stay, and a long-lived process can't fill
        // the whole manifest with what it once looked at.
        Set<String> staleActivations = staleActivations(activatedIn, budget);

        // Group the negotiable rest into (tier, family) buckets.
        Map<GroupKey, List<String>> groups = new LinkedHashMap<>();
        for (String name : primaryIn) {
            if (floor.contains(name)) continue;
            groups.computeIfAbsent(groupKeyFor(name, false, false, h), k -> new ArrayList<>())
                    .add(name);
        }
        for (String name : activatedIn) {
            if (floor.contains(name) || primaryIn.contains(name)) continue;
            boolean stale = staleActivations.contains(name);
            groups.computeIfAbsent(groupKeyFor(name, true, stale, h), k -> new ArrayList<>())
                    .add(name);
        }

        List<Map.Entry<GroupKey, List<String>>> ranked = new ArrayList<>(groups.entrySet());
        ranked.sort(groupComparator(budget));

        Set<String> keepNames = new LinkedHashSet<>(keptFloor);
        // Collected while walking `ranked`, i.e. best-first, then reversed
        // on the way out: "demotion order" means the order things were
        // given up, and the first thing given up is the least important
        // one. See the ordering note on Result#demoted.
        List<List<String>> givenUp = new ArrayList<>();
        List<String> demotedFamilies = new ArrayList<>();
        int remaining = limit - keptFloor.size();
        for (Map.Entry<GroupKey, List<String>> entry : ranked) {
            List<String> members = entry.getValue();
            if (members.size() <= remaining) {
                keepNames.addAll(members);
                remaining -= members.size();
                continue;
            }
            // Whole family goes. Skipping it (instead of stopping) lets a
            // smaller, lower-ranked family still use the leftover slots —
            // the order stays priority-driven either way.
            givenUp.add(members);
            String family = entry.getKey().family();
            if (!demotedFamilies.contains(family)) demotedFamilies.add(family);
        }
        java.util.Collections.reverse(givenUp);
        java.util.Collections.reverse(demotedFamilies);
        List<String> demoted = new ArrayList<>();
        for (List<String> members : givenUp) {
            demoted.addAll(members);
        }

        Set<String> keptPrimary = new LinkedHashSet<>();
        for (String name : primaryIn) {
            if (keepNames.contains(name)) keptPrimary.add(name);
        }
        Set<String> keptActivated = new LinkedHashSet<>();
        for (String name : activatedIn) {
            if (keepNames.contains(name)) keptActivated.add(name);
        }
        return new Result(keptPrimary, keptActivated, List.copyOf(demoted),
                List.copyOf(demotedFamilies), limit);
    }

    /**
     * Order groups by declared class first, then by measured demand
     * (most recently activated, then most-called), then by name so the
     * outcome is reproducible for identical inputs.
     */
    private static Comparator<Map.Entry<GroupKey, List<String>>> groupComparator(
            ToolBudget budget) {
        return Comparator
                .<Map.Entry<GroupKey, List<String>>>comparingInt(e -> e.getKey().tier())
                .thenComparing(e -> recencyOf(e.getValue(), budget), Comparator.reverseOrder())
                .thenComparing(e -> usageOf(e.getValue(), budget), Comparator.reverseOrder())
                .thenComparing(e -> e.getKey().family());
    }

    private static Instant recencyOf(List<String> members, ToolBudget budget) {
        Instant best = Instant.EPOCH;
        for (String name : members) {
            Instant at = budget.activationRecency().get(name);
            if (at != null && at.isAfter(best)) best = at;
        }
        return best;
    }

    private static long usageOf(List<String> members, ToolBudget budget) {
        long sum = 0;
        for (String name : members) {
            Long calls = budget.usage().get(name);
            if (calls != null && calls > 0) sum += calls;
        }
        return sum;
    }

    /**
     * The activations that exceed {@link ToolBudget#maxActivated()},
     * oldest first. Ties (equal or missing timestamps) fall back to the
     * name so the outcome stays reproducible.
     */
    private static Set<String> staleActivations(Set<String> activated, ToolBudget budget) {
        int cap = budget.maxActivated();
        if (cap <= 0 || activated.size() <= cap) return Set.of();
        List<String> byRecency = new ArrayList<>(activated);
        byRecency.sort(Comparator
                .comparing((String n) -> budget.activationRecency()
                        .getOrDefault(n, Instant.EPOCH))
                .reversed()
                .thenComparing(Comparator.naturalOrder()));
        return new LinkedHashSet<>(byRecency.subList(cap, byRecency.size()));
    }

    private static GroupKey groupKeyFor(
            String name, boolean isActivated, boolean staleActivation, Hints hints) {
        if (staleActivation && !hints.matchesKeep(name)) {
            // Past the activation cap: give up the top class and compete
            // last. An explicit per-name keep still overrules the cap.
            return new GroupKey(TIER_DROP_FIRST, ToolFamily.of(name));
        }
        return new GroupKey(tierFor(name, isActivated, hints), ToolFamily.of(name));
    }

    private static int tierFor(String name, boolean isActivated, Hints hints) {
        // Observed demand outranks every declaration: the model already
        // reached for this tool in this process, so the task shape is
        // known rather than predicted.
        if (isActivated) return TIER_ACTIVATED;
        // `keep` wins over `dropFirst` when an author lists a tool in
        // both — the more protective statement stands.
        if (hints.matchesKeep(name)) return TIER_KEEP;
        if (hints.matchesDropFirst(name)) return TIER_DROP_FIRST;
        if (hints.add().contains(name)) return TIER_ADD;
        // Family-level overrides are the coarse fallback — a name hint
        // above already decided the tool.
        String family = ToolFamily.of(name);
        if (hints.keepFamilies().contains(family)) return TIER_KEEP;
        if (hints.dropFirstFamilies().contains(family)) return TIER_DROP_FIRST;
        return ToolFamily.isPackTool(name) ? TIER_PACK : TIER_BUILTIN;
    }

    /**
     * Bucket identity. Tier is part of the key so an explicitly hinted
     * tool splits off from its name-derived family instead of dragging
     * the whole family into its class.
     */
    private record GroupKey(int tier, String family) {}
}
