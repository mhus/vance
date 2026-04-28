/**
 * Persistent layer for {@code Skill}s — reusable capability bundles
 * (description + auto-triggers + prompt extension + tool whitelist +
 * reference docs). Skills compose with recipes: a recipe picks the
 * engine, skills add capabilities. Cascade resolution walks
 * USER → PROJECT → TENANT → BUNDLED; this package owns the three
 * Mongo-stored tiers, bundled defaults are owned by the brain-side
 * {@code BundledSkillRegistry}. See {@code specification/skills.md}.
 */
@NullMarked
package de.mhus.vance.shared.skill;

import org.jspecify.annotations.NullMarked;
