package de.mhus.vance.shared.document.kind;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory model of a {@code kind: diagram} document. The diagram
 * itself is an opaque source string in a renderer-specific DSL —
 * Mermaid in v1 ({@code dialect = "mermaid"}). The codec does not
 * parse the DSL; render-time errors surface in the client.
 *
 * @param kind     always {@code "diagram"}.
 * @param dialect  source dialect. v1 only {@code "mermaid"} is
 *                 actively rendered; unknown values round-trip but
 *                 fall back to the raw editor on the client side.
 * @param diagram  render metadata (theme, look, font). Never
 *                 {@code null} — defaults to {@link DiagramHeader#defaults()}.
 * @param source   the diagram source text. May be empty when the
 *                 document is freshly created; the codec emits a
 *                 warning rather than throwing.
 * @param extra    unknown top-level fields plus reserved
 *                 markdown-roundtrip keys ({@code _preamble},
 *                 {@code _postamble}, {@code _unparsedBody}).
 *
 * <p>Spec: {@code specification/doc-kind-diagram.md}.
 */
public record DiagramDocument(
        String kind,
        String dialect,
        DiagramHeader diagram,
        String source,
        Map<String, Object> extra) {

    public static final String DEFAULT_DIALECT = "mermaid";

    public DiagramDocument {
        if (kind == null || kind.isBlank()) kind = "diagram";
        if (dialect == null || dialect.isBlank()) dialect = DEFAULT_DIALECT;
        if (diagram == null) diagram = DiagramHeader.defaults();
        if (source == null) source = "";
        if (extra == null) extra = new LinkedHashMap<>();
    }

    /** Blank diagram skeleton — empty source, default header. Useful
     *  when the codec is asked for an empty body. */
    public static DiagramDocument empty() {
        return new DiagramDocument(
                "diagram",
                DEFAULT_DIALECT,
                DiagramHeader.defaults(),
                "",
                new LinkedHashMap<>());
    }
}
