package de.mhus.vance.api.addon;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Verification state of an addon's source bundle on the brain
 * filesystem. Returned alongside {@link AddonInsightDto}.
 */
@GenerateTypeScript("addon")
public enum ChecksumStatus {
    /** No checksum configured on the addon row — nothing to verify. */
    NONE,
    /** Checksum configured and the on-disk {@code .vab} hash matches. */
    VERIFIED,
    /**
     * Checksum configured but no source bundle is available on disk
     * to verify against (URL-addon hasn't been fetched yet, or the
     * cache file was wiped).
     */
    UNVERIFIED,
    /** Checksum configured and the on-disk {@code .vab} hash differs. */
    MISMATCH
}
