package de.mhus.vance.api.megadodo;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Where a feed row sits in its {@code traceId} group.
 *
 * <p>{@link #SINGLE} is not a convenience — it is required. "User
 * created" has no duration; forcing every event into a START/END pair
 * would make point-in-time events invent an ending.
 */
@GenerateTypeScript("megadodo")
public enum MegadodoPhase {

    /** Something began. No outcome yet. */
    START,

    /** The matching {@link #START} finished — carries the outcome. */
    END,

    /** Point in time, no duration. Carries the outcome directly. */
    SINGLE
}
