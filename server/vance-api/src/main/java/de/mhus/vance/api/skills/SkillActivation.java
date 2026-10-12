package de.mhus.vance.api.skills;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/**
 * Activation mode of a skill — which surfaces offer it for activation (skills.md §2, planning/
 * skill-activation-levels.md §3.1). A monotone ordering:
 * {@code manual} ⊂ {@code trigger} ⊂ {@code agent} — each level can do
 * everything the previous one can, plus one more surface.
 *
 * <ul>
 *   <li>{@link #MANUAL}: offered nowhere — activated by a deliberate
 *       gesture ({@code /skill}, recipe {@code defaultActiveSkills}) or by
 *       an agent that found it via {@code how_do_i}. Not a permission:
 *       only {@code enabled: false} blocks activation.</li>
 *   <li>{@link #TRIGGER}: word activation on top — the {@code triggers:}
 *       frontmatter block is honored by the auto-trigger matcher.</li>
 *   <li>{@link #AGENT}: the skill is advertised to the LLM (an
 *       "Available skills" card in the turn context) so the agent can
 *       activate it without a discovery round-trip. Triggers stay armed
 *       when the skill declares them.</li>
 * </ul>
 *
 * <p>The mode is configuration (per skill + scope), not state: "is the
 * skill active on this process" lives on {@code ActiveSkillRef} and is a
 * different axis. {@code enabled: false} remains the hard kill switch
 * independent of the mode.
 */
@GenerateTypeScript("skills")
public enum SkillActivation {

    /** Offered nowhere: no word activation, not listed in the turn context. */
    MANUAL,

    /** Word activation: the {@code triggers:} block is armed, plus everything {@link #MANUAL} allows. */
    TRIGGER,

    /** Listed in every turn context ("Available skills") for the agent to activate. Superset of {@link #TRIGGER}. */
    AGENT;

    /** Wire / YAML form — lowercase, matching the SKILL.md frontmatter vocabulary. */
    public String wireName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Parses a frontmatter / override-doc value. {@code null} / blank /
     * unknown values yield {@code null} — callers own the error message
     * with their surrounding context (skill name, document path).
     */
    public static @Nullable SkillActivation parse(@Nullable String raw) {
        if (raw == null) return null;
        String cleaned = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (cleaned) {
            case "manual" -> MANUAL;
            case "trigger" -> TRIGGER;
            case "agent" -> AGENT;
            default -> null;
        };
    }
}
