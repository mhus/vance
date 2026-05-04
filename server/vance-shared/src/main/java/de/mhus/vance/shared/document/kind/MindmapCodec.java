package de.mhus.vance.shared.document.kind;

import org.jspecify.annotations.Nullable;

/**
 * Parser and serialiser for {@code kind: mindmap} documents.
 * Mindmap reuses the tree codec entirely — same on-disk structure,
 * same per-item shape (mindmap-specific fields like {@code color},
 * {@code icon}, {@code link} ride through {@link TreeItem#extra}).
 *
 * <p>The only difference is the canonical {@code kind} stamp: a
 * tree document defaults to {@code "tree"} when {@code kind} is
 * missing on disk, a mindmap document to {@code "mindmap"}.
 *
 * <p>Spec: {@code specification/doc-kind-mindmap.md} §3.4 — codec
 * deliberately not duplicated.
 */
public final class MindmapCodec {

    private MindmapCodec() {
        // utility class
    }

    /**
     * Parse a mindmap body. Returns a {@link TreeDocument} whose
     * {@code kind} is preserved from the body if present, or
     * defaulted to {@code "mindmap"} if missing — distinct from
     * {@link TreeCodec#parse} which defaults to {@code "tree"}.
     */
    public static TreeDocument parse(String body, @Nullable String mimeType) {
        return TreeCodec.parse(body, mimeType, "mindmap");
    }

    /**
     * Serialise a mindmap document. The caller is expected to set
     * {@link TreeDocument#kind()} to {@code "mindmap"} (or leave it
     * blank — the writer defaults to whatever {@code kind} the doc
     * carries, falling back to {@code "tree"}; for mindmap documents
     * the kind should be explicitly {@code "mindmap"}).
     */
    public static String serialize(TreeDocument doc, @Nullable String mimeType) {
        return TreeCodec.serialize(doc, mimeType);
    }

    public static boolean supports(@Nullable String mimeType) {
        return TreeCodec.supports(mimeType);
    }
}
