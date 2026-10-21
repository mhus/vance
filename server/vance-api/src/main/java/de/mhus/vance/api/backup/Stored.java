package de.mhus.vance.api.backup;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * How a backup file was placed in the snapshot. Lives at the top level of
 * the {@code backup} package (not nested in {@link BackupEntryDto}) because
 * the TS generator emits one file per top-level type — a nested enum
 * produces a forward reference the generator cannot resolve.
 */
@GenerateTypeScript("backup")
public enum Stored {
    /** New download this run. */
    DOWNLOADED,
    /** Hardlink to the previous snapshot's file. */
    LINKED,
    /** Server returned 404/5xx for the content; row kept for the manifest. */
    FAILED
}
