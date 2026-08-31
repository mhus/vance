package de.mhus.vance.addon.brain.links;

/**
 * Body of {@code POST /brain/{tenant}/addon/links/entry/viewed}.
 *
 * <p>{@code viewed} is required rather than a toggle, so the same request sent
 * twice lands in the same state. A toggle would make a retried click — the one
 * a flaky connection produces — undo itself.
 */
public record SetViewedRequest(String url, boolean viewed) {}
