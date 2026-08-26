package de.mhus.vance.addon.brain.bistromath;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * How strictly a tenant handles custom applications.
 *
 * <p>Top-level rather than nested in {@link AppPolicy} because it crosses to
 * TypeScript, and a nested enum is a name the generator cannot resolve on the
 * other side.
 */
@GenerateTypeScript("bistromath")
public enum AppMode {
    /** No app runs. */
    FORBIDDEN,
    /** Runs, with the policy's route list applied. */
    RESTRICTED,
    /** As before this feature existed. */
    ALLOWED
}
