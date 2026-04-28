package de.mhus.vance.api.skills;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * How an auto-trigger matches against incoming user input.
 */
@GenerateTypeScript("skills")
public enum SkillTriggerType {
    /** Regex match against the raw user input. */
    PATTERN,
    /**
     * Bag-of-keywords match. The trigger fires when at least half of the
     * configured keywords appear (case-insensitive, whitespace-tokenized)
     * in the user input.
     */
    KEYWORDS
}
