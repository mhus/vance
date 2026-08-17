package de.mhus.vance.api.kit;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * How strictly a source's signatures are enforced.
 *
 * <p>Per source rather than global, because the two ends of the range
 * are both legitimate at the same time: kits shared between colleagues
 * over git are not signed and requiring it would break every existing
 * install, while a kit that was paid for has no excuse.
 */
@GenerateTypeScript("kit")
public enum KitSignaturePolicy {

    /** No signature expected. Default for git and folder sources. */
    OFF,

    /** Verify when present, log when absent or bad. For migrating a source. */
    WARN,

    /** Refuse anything unsigned or badly signed. Default for library sources. */
    REQUIRED;

    /** What applies to a source that does not say. */
    public static KitSignaturePolicy defaultFor(KitSourceType type) {
        return type == KitSourceType.LIBRARY ? REQUIRED : OFF;
    }
}
