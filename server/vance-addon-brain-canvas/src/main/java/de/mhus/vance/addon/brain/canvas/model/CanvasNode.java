package de.mhus.vance.addon.brain.canvas.model;

import org.jspecify.annotations.Nullable;

/**
 * A node on a canvas. Sealed over the four v1 node types
 * (see {@code planning/canvas.md} §4.2).
 *
 * <p>Every node carries the common geometry ({@code id}, {@code x},
 * {@code y}, {@code w}, {@code h}) plus optional {@code color} (palette
 * index {@code "1"}–{@code "6"} or hex), {@code z} stacking order and
 * {@code parent} (id of the containing {@code group} node — makes group
 * membership a real, LLM-readable structure). When {@code parent} is set,
 * {@code x}/{@code y} are relative to the parent group's top-left corner.
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

    /** Id of the containing group, or {@code null} when at canvas root. */
    @Nullable String parent();

    /** Discriminator string as written to disk ({@code type:}). */
    String type();

    /** Inline text node — content lives in the graph document. */
    record Text(String id, double x, double y, double w, double h,
                @Nullable String color, @Nullable Integer z, @Nullable String parent,
                String text,
                @Nullable Boolean bold, @Nullable Boolean italic,
                @Nullable String fontSize, @Nullable String textColor,
                @Nullable String author) implements CanvasNode {
        @Override public String type() { return "text"; }
    }

    /** Reference node — {@code ref} is a {@code vance:}-URI to a document. */
    record Doc(String id, double x, double y, double w, double h,
               @Nullable String color, @Nullable Integer z, @Nullable String parent,
               String ref) implements CanvasNode {
        @Override public String type() { return "doc"; }
    }

    /** External link card. */
    record Link(String id, double x, double y, double w, double h,
                @Nullable String color, @Nullable Integer z, @Nullable String parent,
                String href, @Nullable String title) implements CanvasNode {
        @Override public String type() { return "link"; }
    }

    /**
     * Labelled background rectangle. Membership of other nodes is
     * expressed by their {@code parent} pointing at this group's id.
     */
    record Group(String id, double x, double y, double w, double h,
                 @Nullable String color, @Nullable Integer z, @Nullable String parent,
                 @Nullable String label) implements CanvasNode {
        @Override public String type() { return "group"; }
    }
}
