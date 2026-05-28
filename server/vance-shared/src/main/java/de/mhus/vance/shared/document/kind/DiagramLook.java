package de.mhus.vance.shared.document.kind;

import org.jspecify.annotations.Nullable;

/**
 * Mermaid render look — classic SVG strokes or the rough.js hand-drawn
 * sketch style introduced in Mermaid 10. Unknown wire values are
 * clamped to {@link #CLASSIC} by the codec.
 *
 * <p>Spec: {@code specification/doc-kind-diagram.md} §2.2.
 */
public enum DiagramLook {

    CLASSIC("classic"),
    HAND_DRAWN("handDrawn");

    private final String wire;

    DiagramLook(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static @Nullable DiagramLook fromWire(@Nullable String wire) {
        if (wire == null) return null;
        for (DiagramLook l : values()) {
            if (l.wire.equals(wire)) return l;
        }
        return null;
    }
}
