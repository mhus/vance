package de.mhus.vance.toolpack.facet;

import org.jspecify.annotations.Nullable;

/**
 * One selectable value of a {@link Facet}.
 *
 * <p>{@code id} is what travels in the request and is opaque to us;
 * {@code label} is what a person picking it sees. For a hierarchical facet
 * {@code parentId} names the value one level up, and the root has none — that
 * is the whole tree, flat, because a nested structure would have to be walked
 * to be rendered and this one is indexed by id in a single pass.
 *
 * <p>The label is <b>single-language, as the source wrote it</b>. Translating
 * it here would be the first place Vancetope claims to know the semantics of a
 * foreign facet; see {@code planning/centauri-facets.md} §3.4.
 */
public record FacetValue(String id, String label, @Nullable String parentId) {

    public FacetValue {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("facet value id is required");
        }
        id = id.trim();
        label = label == null || label.isBlank() ? id : label.trim();
        parentId = parentId == null || parentId.isBlank() ? null : parentId.trim();
    }

    /** A root value — one with no parent. */
    public static FacetValue of(String id, String label) {
        return new FacetValue(id, label, null);
    }
}
