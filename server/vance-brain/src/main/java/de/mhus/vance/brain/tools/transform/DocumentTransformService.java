package de.mhus.vance.brain.tools.transform;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.toolpack.ToolException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Dispatch layer over {@link DocumentTransformer} beans. Adding a
 * new (source-shape, target-format) pair is a matter of dropping
 * another {@code @Component} that implements the interface — no
 * registration boilerplate.
 */
@Service
@Slf4j
public class DocumentTransformService {

    /** Lookup by target-format key. A given target format has
     *  potentially multiple transformers (one per source shape);
     *  {@code dispatch} narrows further via {@code canTransform}. */
    private final Map<String, List<DocumentTransformer>> byFormat;

    public DocumentTransformService(List<DocumentTransformer> transformers) {
        Map<String, List<DocumentTransformer>> map = new HashMap<>();
        for (DocumentTransformer t : transformers) {
            map.computeIfAbsent(
                    t.targetFormat().toLowerCase(Locale.ROOT),
                    k -> new java.util.ArrayList<>()
            ).add(t);
        }
        this.byFormat = Map.copyOf(map);
        log.info("DocumentTransformService bootstrapped: formats={}",
                byFormat.keySet());
    }

    public Set<String> supportedFormats() {
        return byFormat.keySet();
    }

    /**
     * Pick the right transformer for the (source, target-format)
     * pair. Throws {@link ToolException} with a helpful message
     * when nothing matches.
     */
    public DocumentTransformer dispatch(DocumentDocument source, String format) {
        String key = format.toLowerCase(Locale.ROOT);
        List<DocumentTransformer> candidates = byFormat.get(key);
        if (candidates == null) {
            throw new ToolException(
                    "Unsupported target format '" + format + "' — "
                            + "supported: " + byFormat.keySet());
        }
        for (DocumentTransformer t : candidates) {
            if (t.canTransform(source)) return t;
        }
        throw new ToolException(
                "No transformer can convert document '"
                        + source.getPath() + "' (kind='"
                        + source.getKind() + "', mime='"
                        + source.getMimeType() + "') to format '"
                        + format + "'. Available source families "
                        + "for this format: "
                        + supportedSourcesFor(format));
    }

    private Set<String> supportedSourcesFor(String format) {
        Set<String> out = new LinkedHashSet<>();
        List<DocumentTransformer> list =
                byFormat.get(format.toLowerCase(Locale.ROOT));
        if (list == null) return out;
        for (DocumentTransformer t : list) {
            out.add(t.getClass().getSimpleName());
        }
        return out;
    }

    /**
     * Infer the target format from a {@code toDocument} path
     * extension when the caller didn't pass {@code format}
     * explicitly. Returns {@code null} when the extension is
     * unknown — the controller then has to require explicit
     * {@code format}.
     */
    public @Nullable String inferFormat(@Nullable String toDocumentPath) {
        if (toDocumentPath == null || toDocumentPath.isBlank()) return null;
        int dot = toDocumentPath.lastIndexOf('.');
        if (dot < 0 || dot == toDocumentPath.length() - 1) return null;
        String ext = toDocumentPath.substring(dot + 1).toLowerCase(Locale.ROOT);
        return byFormat.containsKey(ext) ? ext : null;
    }
}
