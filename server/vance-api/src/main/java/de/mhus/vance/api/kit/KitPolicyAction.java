package de.mhus.vance.api.kit;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * How a kit update treats a single artefact that already exists in the
 * project. Selected per artefact by {@link KitPolicyDto} — the default
 * applies unless a later-matching rule overrides it.
 *
 * <p>{@code KEEP} and {@code IGNORE} both mean "do not overwrite", but
 * for different reasons and in different situations: {@code KEEP} keeps
 * the kit alive and only protects edits the user actually made,
 * {@code IGNORE} freezes the artefact outright.
 *
 * <p>Spec: {@code planning/kit-installed-multi.md} §D7 / §5.
 */
@GenerateTypeScript("kit")
public enum KitPolicyAction {

    /**
     * Default. Write when the artefact is absent or still carries the
     * hash recorded at install time; skip when the user changed it.
     */
    KEEP,

    /** The kit always wins — write unconditionally. */
    OVERWRITE,

    /** Never write, not even when the artefact is untouched. */
    IGNORE,

    /**
     * Like {@link #KEEP}, but instead of leaving a locally modified
     * artefact alone, merge the kit's new version into it three-way
     * against the version the kit last installed.
     *
     * <p>Documents only — merging two versions of a single setting value
     * line by line would be theatre, so on settings this behaves as
     * {@link #KEEP}. Falls back to {@link #KEEP} as well when the
     * previous state cannot be reconstructed (folder sources have no
     * commit to check out).
     */
    MERGE;

    /**
     * Parse a YAML value, tolerating case and surrounding whitespace.
     *
     * @param raw value as written in the config document
     * @return the matching action
     * @throws IllegalArgumentException when {@code raw} names no action —
     *         callers wrap this into a kit-level error carrying the file name
     */
    public static KitPolicyAction parse(String raw) {
        return valueOf(raw.trim().toUpperCase());
    }
}
