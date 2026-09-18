package de.mhus.vance.addon.brain.scribble.model;

/**
 * One input sample of a stroke: sheet-space integer coordinates plus pen
 * {@code pressure} in {@code [0, 1]}. Coordinates are already simplified
 * (RDP) and rounded by the client before they are committed — the raw
 * 240 Hz pointer stream is never persisted.
 */
public record ScribblePoint(int x, int y, double pressure) {}
