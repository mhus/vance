package de.mhus.vance.brain.skill;

import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Matches recipe {@code relevantSkills} selectors against skills
 * (skills.md §7e, planning/skill-activation-levels.md §7.5) — the twin of
 * the tool pool's label selection: the <b>recipe</b> says what
 * <em>kind</em> of skill makes sense for it ("everything that contributes
 * to coding"), the <b>skill</b> decides membership by tagging itself.
 * Neither side has to know the other.
 *
 * <p>Entry forms:
 * <ul>
 *   <li>{@code @label} — every skill whose {@code tags:} carry the label
 *       (case-insensitive, trimmed);</li>
 *   <li>{@code name} — exactly that skill;</li>
 *   <li>{@code !@label} / {@code !name} — exclusion: the skill is not
 *       relevant even if an inclusion selects it.</li>
 * </ul>
 * A list of exclusions only starts from "everything" ({@code ["!@coding"]}
 * = all but the coding skills); a list with inclusions starts from
 * "nothing". An empty list selects nothing; an absent field (callers pass
 * {@code null}) selects everything.
 *
 * <p>Selection is relevance only: an irrelevant skill's effective level is
 * {@code hidden}, a relevant one keeps the level the skill declares. It
 * never activates anything — which is why {@code defaultActiveSkills} and
 * Benjy stage skills reject selectors outright.
 */
public final class SkillLabelSelector {

    public static final String SELECTOR_PREFIX = "@";

    public static final String EXCLUDE_PREFIX = "!";

    private SkillLabelSelector() {}

    /** Whether one list entry is a label selector (possibly excluded) rather than an exact name. */
    public static boolean isLabelSelector(@Nullable String entry) {
        return entry != null && positive(entry).startsWith(SELECTOR_PREFIX);
    }

    /** Whether one list entry is an exclusion ({@code !…}). */
    public static boolean isExclusion(@Nullable String entry) {
        return entry != null && entry.strip().startsWith(EXCLUDE_PREFIX);
    }

    /** The label of a selector entry, normalised (trimmed, lower-case), without {@code !} and {@code @}. */
    public static String labelOf(String selector) {
        return positive(selector).substring(SELECTOR_PREFIX.length()).trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Whether {@code skill} is relevant under {@code selectors}:
     * {@code null} (field absent) → always; otherwise no exclusion may
     * match, and an inclusion must match unless the list holds
     * exclusions only.
     */
    public static boolean relevant(@Nullable List<String> selectors, ResolvedSkill skill) {
        if (selectors == null) return true;
        boolean anyInclusion = false;
        boolean included = false;
        for (String entry : selectors) {
            if (entry == null || entry.isBlank()) continue;
            boolean matches = matches(positive(entry), skill);
            if (isExclusion(entry)) {
                if (matches) return false;
            } else {
                anyInclusion = true;
                included |= matches;
            }
        }
        if (anyInclusion) return included;
        // Exclusions only → "everything else"; no usable entry at all
        // (an explicit empty list) → nothing.
        return selectors.stream().anyMatch(SkillLabelSelector::isExclusion);
    }

    /**
     * Whether an entry is malformed — an empty label ({@code @},
     * {@code !@}) or a bare {@code !}. Used by the recipe loader to fail
     * fast; {@link #relevant} skips such entries.
     */
    public static boolean isMalformed(String entry) {
        String body = positive(entry);
        if (body.isEmpty()) return true;
        return isLabelSelector(entry) && labelOf(entry).isEmpty();
    }

    private static boolean matches(String body, ResolvedSkill skill) {
        if (body.isEmpty()) return false;
        if (!body.startsWith(SELECTOR_PREFIX)) return body.equals(skill.name());
        String label = body.substring(SELECTOR_PREFIX.length()).trim().toLowerCase(Locale.ROOT);
        return !label.isEmpty() && carriesLabel(skill, label);
    }

    /** The entry without its exclusion prefix, trimmed. */
    private static String positive(String entry) {
        String s = entry.strip();
        return s.startsWith(EXCLUDE_PREFIX)
                ? s.substring(EXCLUDE_PREFIX.length()).strip()
                : s;
    }

    private static boolean carriesLabel(ResolvedSkill skill, String label) {
        for (String tag : skill.tags()) {
            if (tag == null || tag.isBlank()) continue;
            if (tag.strip().toLowerCase(Locale.ROOT).equals(label)) return true;
        }
        return false;
    }
}
