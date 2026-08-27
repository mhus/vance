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

    /**
     * No signature expected. The default for everything but a library — git
     * and folder because requiring it would break every existing install, and
     * {@link KitSourceType#PROJECT} because there is nothing to authenticate:
     * the tree is written out of our own database, so a signature would verify
     * us against ourselves.
     */
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
