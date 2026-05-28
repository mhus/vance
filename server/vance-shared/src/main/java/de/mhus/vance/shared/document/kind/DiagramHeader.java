package de.mhus.vance.shared.document.kind;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Document-level diagram metadata: theme, render look, optional font.
 * Everything else relevant to the diagram lives inside the
 * {@link DiagramDocument#source()} string and is the renderer's concern.
 *
 * <p>{@code extra} captures unknown header keys so future Mermaid
 * config options ride through round-trip-stable without a codec
 * change.
 *
 * <p>Spec: {@code specification/doc-kind-diagram.md} §2.2.
 */
public record DiagramHeader(
        DiagramTheme theme,
        DiagramLook look,
        @Nullable String fontFamily,
        Map<String, Object> extra) {

    public DiagramHeader {
        if (theme == null) theme = DiagramTheme.DEFAULT;
        if (look == null) look = DiagramLook.CLASSIC;
        if (extra == null) extra = new LinkedHashMap<>();
    }

    /** Default header — Mermaid's plain defaults, no font override. */
    public static DiagramHeader defaults() {
        return new DiagramHeader(DiagramTheme.DEFAULT, DiagramLook.CLASSIC, null, new LinkedHashMap<>());
    }

    /** True when every field still matches {@link #defaults()} — used
     *  by the serialiser to decide whether to emit the {@code diagram:}
     *  block at all (omitted when redundant). */
    public boolean isDefault() {
        return theme == DiagramTheme.DEFAULT
                && look == DiagramLook.CLASSIC
                && fontFamily == null
                && extra.isEmpty();
    }
}
