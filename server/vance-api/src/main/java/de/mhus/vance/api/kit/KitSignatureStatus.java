package de.mhus.vance.api.kit;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * What the signature check said when a kit was installed.
 *
 * <p>Recorded, not re-derived: verifying again later would need the kit
 * tree, which is long gone — what is in the project are the artefacts,
 * not the bundle they arrived in. So this is explicitly a statement
 * about the moment of installation, and the next update replaces it.
 */
@GenerateTypeScript("kit")
public enum KitSignatureStatus {

    /** Signature present, key known, content matched. */
    VERIFIED,

    /**
     * No signature, and none was required. The normal state for kits
     * from git — not a defect, and shown as plainly as that.
     */
    UNSIGNED,

    /**
     * Signature checked and rejected, but the source's policy was
     * {@code warn}, so it was installed anyway. The one state worth
     * looking at twice.
     */
    FAILED
}
