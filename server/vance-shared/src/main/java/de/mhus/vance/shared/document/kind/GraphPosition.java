package de.mhus.vance.shared.document.kind;

/**
 * 2D position for a {@link GraphNode}. Coordinates use the same
 * convention as vue-flow on the client: top-left origin, X grows
 * to the right, Y grows downwards. Both fields are required.
 */
public record GraphPosition(double x, double y) {}
