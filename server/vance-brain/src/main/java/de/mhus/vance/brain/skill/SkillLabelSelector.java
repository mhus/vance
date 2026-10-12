package de.mhus.vance.brain.skill;

import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Matches recipe {@code advertisedSkills} selectors against skills
 * (skills.md §7e) — the twin of the tool pool's label selection: the
 * <b>recipe</b> says what <em>kind</em> of skill its agent should be offered
 * ("everything that contributes to coding"), the <b>skill</b> decides
 * membership by tagging itself. Neither side has to know the other.
 *
 * <p>An entry starting with {@code @} selects every skill whose
 * {@code tags:} carry the label (case-insensitive, trimmed); any other
 * entry is an exact skill name.
 *
 * <p>Selection is discovery only: a selected skill lands in the turn
 * context's "Available skills" list, from where the agent activates it via
 * {@code skill_activate}. It never activates anything by itself — which is
 * why {@code defaultActiveSkills} and Benjy stage skills reject label
 * selectors outright.
 */
public final class SkillLabelSelector {

    public static final String SELECTOR_PREFIX = "@";

    private SkillLabelSelector() {}

    /** Whether one list entry is a label selector rather than an exact name. */
    public static boolean isLabelSelector(@Nullable String entry) {
        return entry != null && entry.strip().startsWith(SELECTOR_PREFIX);
    }

    /** The label of a selector entry, normalised (trimmed, lower-case). */
    public static String labelOf(String selector) {
        return selector.strip().substring(SELECTOR_PREFIX.length()).trim().toLowerCase(Locale.ROOT);
    }

    /** Whether any of {@code selectors} selects {@code skill} — by exact name or by tag. */
    public static boolean selects(List<String> selectors, ResolvedSkill skill) {
        for (String entry : selectors) {
            if (entry == null || entry.isBlank()) continue;
            if (!isLabelSelector(entry)) {
                if (entry.strip().equals(skill.name())) return true;
                continue;
            }
            String label = labelOf(entry);
            if (!label.isEmpty() && carriesLabel(skill, label)) return true;
        }
        return false;
    }

    private static boolean carriesLabel(ResolvedSkill skill, String label) {
        for (String tag : skill.tags()) {
            if (tag == null || tag.isBlank()) continue;
            if (tag.strip().toLowerCase(Locale.ROOT).equals(label)) return true;
        }
        return false;
    }
}
