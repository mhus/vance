package de.mhus.vance.api.skills;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Discriminator for {@link ProcessSkillRequest#getCommand()}.
 *
 * <ul>
 *   <li>{@link #ACTIVATE} — add the skill to the process's
 *       {@code activeSkills} (sticky by default; one-shot when
 *       {@code oneShot=true}). Idempotent: re-activating an already
 *       active skill keeps the existing entry.</li>
 *   <li>{@link #CLEAR} — remove a single named skill. No-op if the
 *       skill is not currently active. Recipe-bound skills on a locked
 *       recipe cannot be cleared by the user.</li>
 *   <li>{@link #CLEAR_ALL} — remove all user/auto skills. Recipe-bound
 *       skills are kept when the recipe is locked.</li>
 *   <li>{@link #LIST} — read-only: returns the current
 *       {@code activeSkills} plus the {@code listAvailable} cascade
 *       view in the response, no mutation.</li>
 * </ul>
 */
@GenerateTypeScript("skills")
public enum ProcessSkillCommand {
    ACTIVATE,
    CLEAR,
    CLEAR_ALL,
    LIST
}
