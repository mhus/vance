package de.mhus.vance.api.skills;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/**
 * Activation level of a skill — where it is offered once it is relevant
 * (skills.md §2, planning/skill-activation-levels.md §7). One ladder,
 * ordered {@code hidden < manual < trigger < agent}:
 *
 * <ul>
 *   <li>{@link #HIDDEN}: not meaningful here — hidden in the skill panel
 *       by default (a toggle shows it), no word activation, not listed for
 *       the AI. Also the effective level of every skill the process
 *       recipe's {@code relevantSkills} does not select.</li>
 *   <li>{@link #MANUAL}: shown in the panel, activated by a deliberate
 *       gesture only ({@code /skill}, recipe {@code defaultActiveSkills},
 *       an agent that found it via {@code how_do_i}).</li>
 *   <li>{@link #TRIGGER}: word activation — the {@code triggers:}
 *       frontmatter block is honored by the auto-trigger matcher.</li>
 *   <li>{@link #AGENT}: listed under "Available skills" in the turn
 *       context; the model decides via {@code skill_activate}. Triggers
 *       are not armed — a word match would pre-empt that decision.</li>
 * </ul>
 *
 * <p>No level is a permission: every enabled skill stays activatable by
 * a deliberate gesture, including a hidden one. Only {@code enabled:
 * false} and the recipe's {@code allowedSkills} whitelist block it. The
 * level is configuration (per skill + scope, filtered by the recipe), not
 * state: "is the skill active on this process" lives on
 * {@code ActiveSkillRef} and is a different axis.
 */
@GenerateTypeScript("skills")
public enum SkillActivation {

    /** Not meaningful in this context: hidden by default, offered nowhere, still activatable on purpose. */
    HIDDEN,

    /** Shown, offered nowhere: no word activation, not listed in the turn context. */
    MANUAL,

    /** Word activation: the {@code triggers:} block is armed. */
    TRIGGER,

    /** Listed in the turn context ("Available skills") for the agent to activate; triggers are not armed. */
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
            case "hidden" -> HIDDEN;
            case "manual" -> MANUAL;
            case "trigger" -> TRIGGER;
            case "agent" -> AGENT;
            default -> null;
        };
    }
}
