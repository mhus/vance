/**
 * Brain-side skill machinery — cascade resolver, bundled-skill loader,
 * prompt composer, REST admin controller. Skills are reusable
 * capability bundles (description + auto-triggers + prompt extension +
 * tool whitelist + reference docs) that compose with recipes; this
 * package owns the BUNDLED tier and the cascade layer that sits on top
 * of {@code SkillService} from {@code vance-shared}. See
 * {@code specification/skills.md}.
 */
@NullMarked
package de.mhus.vance.brain.skill;

import org.jspecify.annotations.NullMarked;
