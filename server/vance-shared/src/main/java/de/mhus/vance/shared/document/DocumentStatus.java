package de.mhus.vance.shared.document;

/**
 * Lifecycle state of a {@link DocumentDocument}.
 */
public enum DocumentStatus {

    /** Normal, listed, searchable. Default on creation. */
    ACTIVE,

    /** Hidden from default listings but kept for history / retrieval by id. */
    ARCHIVED
}
