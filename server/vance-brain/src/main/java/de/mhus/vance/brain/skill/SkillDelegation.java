package de.mhus.vance.brain.skill;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * What happens to an active skill when its process delegates the work to a
 * worker ({@code process_spawn}, Arthur's {@code DELEGATE}) — the
 * {@code delegation:} frontmatter field (skills.md §7f).
 */
public enum SkillDelegation {

    /**
     * The default: the skill goes along — whoever does the task needs its
     * instructions (a report format, a debugging method, a review list).
     */
    CARRY,

    /**
     * The skill stays with the delegating process — it is about the
     * conversation itself (helping the user decide, a chat persona), not
     * about the delegated work.
     */
    STAY;

    /** Parses a frontmatter value; {@code null} for blank / unknown — callers own the error. */
    public static @Nullable SkillDelegation parse(@Nullable String raw) {
        if (raw == null) return null;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "carry" -> CARRY;
            case "stay" -> STAY;
            default -> null;
        };
    }
}
