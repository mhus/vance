package de.mhus.vance.addon.brain.canvas.model;

import org.jspecify.annotations.Nullable;

/**
 * A node on a canvas. Sealed over the four v1 node types
 * (see {@code planning/canvas.md} §4.2).
 *
 * <p>Every node carries the common geometry ({@code id}, {@code x},
 * {@code y}, {@code w}, {@code h}) plus optional {@code color} (palette
 * index {@code "1"}–{@code "6"} or hex) and {@code z} stacking order.
 * The {@code id} is persistent — edges reference it.
 */
public sealed interface CanvasNode
        permits CanvasNode.Text, CanvasNode.Doc, CanvasNode.Link, CanvasNode.Group {

    String id();

    double x();

    double y();

    double w();

    double h();

    @Nullable String color();

    @Nullable Integer z();

    /** Discriminator string as written to disk ({@code type:}). */
    String type();

    /**
     * Inline text node — content lives in the graph document. Carries
     * node-level text styling ({@code bold}/{@code italic}/{@code fontSize}
     * = {@code "s"|"m"|"l"}); {@code color} is the sticky-note background.
     */
    record Text(String id, double x, double y, double w, double h,
                @Nullable String color, @Nullable Integer z,
                String text,
                @Nullable Boolean bold, @Nullable Boolean italic,
                @Nullable String fontSize) implements CanvasNode {
        @Override public String type() { return "text"; }
    }

    /** Reference node — {@code ref} is a {@code vance:}-URI to a document. */
    record Doc(String id, double x, double y, double w, double h,
               @Nullable String color, @Nullable Integer z,
               String ref) implements CanvasNode {
        @Override public String type() { return "doc"; }
    }

    /** External link card. */
    record Link(String id, double x, double y, double w, double h,
                @Nullable String color, @Nullable Integer z,
                String href, @Nullable String title) implements CanvasNode {
        @Override public String type() { return "link"; }
    }

    /**
     * Labelled background rectangle. Membership is implicit (spatial
     * containment) — a group holds no child references.
     */
    record Group(String id, double x, double y, double w, double h,
                 @Nullable String color, @Nullable Integer z,
                 @Nullable String label) implements CanvasNode {
        @Override public String type() { return "group"; }
    }
}
