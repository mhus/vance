package de.mhus.vance.shared.document.kind;

import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationContext;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

/**
 * Generic {@link KindHandler} for a codec-backed kind whose validation baseline
 * is simply "does it parse?". Runs the kind's codec and reports a parse failure
 * (structural malformation) as an {@code ERROR} {@link Finding}
 * ({@code <kind>-parse}) rather than letting the {@code KindCodecException}
 * escape — so {@code kind_validate} on a {@code sheet} / {@code chart} /
 * {@code graph} / … actually checks structure instead of silently passing
 * (a name-only handler's {@code validate} returns empty).
 *
 * <p>Configured once per kind in {@code CodecKindHandlers} with the codec's
 * {@code parse} + {@code supports} method references. Semantic (beyond-parse)
 * checks are added later by promoting a kind to its own dedicated handler
 * (as {@code records} / {@code canvas} did) — this is the cheap baseline that
 * lifts every codec-backed built-in above "name-only".
 */
public final class CodecKindHandler implements KindHandler {

    private static final String MD = "text/markdown";
    private static final String JSON = "application/json";
    private static final String YAML = "application/yaml";

    private final String kind;
    private final BiFunction<String, String, Object> parser;
    private final Predicate<String> supports;

    public CodecKindHandler(
            String kind,
            BiFunction<String, String, Object> parser,
            Predicate<String> supports) {
        this.kind = kind;
        this.parser = parser;
        this.supports = supports;
    }

    @Override
    public String getName() {
        return kind;
    }

    @Override
    public List<Finding> validate(String content, KindValidationContext ctx) {
        String target = StringUtils.isBlank(ctx.docPath()) ? kind : ctx.docPath();
        String mime = resolveMime(ctx.mimeType(), content);
        try {
            parser.apply(content, mime);
            return List.of();
        } catch (RuntimeException e) {
            return List.of(Finding.error(target, kind + "-parse",
                    "not a valid " + kind + ": " + e.getMessage()));
        }
    }

    /**
     * The context's mime when the codec accepts it, otherwise the first sniffed
     * candidate the codec accepts (by-path always has a real mime; by-value
     * falls back to a content sniff).
     */
    private String resolveMime(@Nullable String ctxMime, String content) {
        if (ctxMime != null && supports.test(ctxMime)) return ctxMime;
        List<String> order = sniffOrder(content);
        for (String m : order) {
            if (supports.test(m)) return m;
        }
        return order.get(0);
    }

    private static List<String> sniffOrder(String content) {
        String t = content.stripLeading();
        if (t.startsWith("{") || t.startsWith("[")) return List.of(JSON, YAML, MD);
        if (t.startsWith("---")) return List.of(MD, YAML, JSON);
        return List.of(YAML, MD, JSON);
    }
}
