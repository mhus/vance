package de.mhus.vance.foot.ui;

/**
 * Edge callbacks for the {@link BusyIndicator} in-flight counter.
 * Fired on the two boundary transitions only — <em>not</em> on every
 * nested {@code enter}/{@code exit} — so implementations see a clean
 * "work started" / "work settled" pair regardless of how many
 * overlapping operations stacked in between.
 *
 * <p>Any registered Spring bean implementing this interface is picked
 * up automatically by {@link BusyIndicator}. Callbacks run on the
 * caller's thread; implementations must be fast and must not throw —
 * {@link BusyIndicator} swallows and logs exceptions so a misbehaving
 * listener can never break the busy-state bookkeeping.
 */
public interface BusyListener {

    /** The in-flight counter rose from 0 to 1 — real work just began. */
    void onBusyStart();

    /** The in-flight counter fell back to 0 — everything has settled. */
    void onBusyEnd();
}
