package de.mhus.vance.shared.document.kind;

import org.jspecify.annotations.Nullable;

/**
 * Mermaid built-in themes mirrored on the Vance side. Anything outside
 * this set is clamped to {@link #DEFAULT} by the codec — Mermaid would
 * otherwise silently fall back at render time, leaving the on-disk
 * value out of sync with what the user actually sees.
 *
 * <p>Spec: {@code specification/doc-kind-diagram.md} §2.2.
 */
public enum DiagramTheme {

    DEFAULT("default"),
    DARK("dark"),
    FOREST("forest"),
    NEUTRAL("neutral"),
    BASE("base");

    private final String wire;

    DiagramTheme(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static @Nullable DiagramTheme fromWire(@Nullable String wire) {
        if (wire == null) return null;
        for (DiagramTheme t : values()) {
            if (t.wire.equals(wire)) return t;
        }
        return null;
    }
}
