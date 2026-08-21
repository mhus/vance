package de.mhus.vance.api.documents;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * What a mounted document's backing source allows. Carried on
 * {@code DocumentDto} (and as a transient field on the persisted document)
 * for documents under the {@code _ext/} namespace; {@code null} for every
 * ordinary Vance document.
 *
 * <p>The purpose is <b>client ergonomics</b>, not enforcement: an editor
 * that sees {@link #RO} can render read-only instead of offering a save
 * that is going to be refused. Enforcement happens on the write attempt,
 * where this sits next to two other, independent reasons a write can fail
 * — the soft document lock ({@code lockedFor}) and the permission layer.
 * A client must never treat {@link #RW} as permission to write.
 *
 * <p>{@link #UNKNOWN} is a real state, not a missing value: a configured
 * mount whose source is currently unreachable is reported as UNKNOWN rather
 * than hidden, because "not configured" and "not answering right now" are
 * different facts and only the first justifies disappearing from the tree.
 * This is why the type is an enum and not a boolean.
 */
@GenerateTypeScript("documents")
public enum MountAccess {

    /** Source not reachable, or it declares nothing about write access. */
    UNKNOWN,

    /** Readable only — writes are refused by the source. */
    RO,

    /** Readable and writable. Says nothing about the caller's permission. */
    RW
}
