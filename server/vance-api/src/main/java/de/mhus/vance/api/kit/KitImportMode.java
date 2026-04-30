package de.mhus.vance.api.kit;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Operation mode for a kit import. {@code INSTALL} writes a manifest
 * for the first time; {@code UPDATE} re-runs against the same source
 * and rewrites the manifest; {@code APPLY} splats artefacts without
 * tracking — the project gains the files but loses the kit's identity.
 */
@GenerateTypeScript("kit")
public enum KitImportMode {
    INSTALL,
    UPDATE,
    APPLY
}
