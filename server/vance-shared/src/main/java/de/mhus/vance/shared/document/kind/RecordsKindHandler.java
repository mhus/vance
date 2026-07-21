package de.mhus.vance.shared.document.kind;

import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationContext;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * {@link KindHandler} for the built-in {@code records} kind — the first kind
 * with real {@link #validate} logic (kind-handler track Phase 3). Extracted
 * from the trivial lambda bean in {@code BuiltInKindHandlers} exactly as its
 * javadoc anticipated ("later, when an extension point grows … individual
 * entries can be extracted into their own {@code @Service} classes").
 *
 * <p>Validation runs the canonical {@link RecordsCodec} — a parse failure
 * (e.g. the schema-required rule) is surfaced as an {@code ERROR} finding
 * rather than a thrown {@link KindCodecException}, so the report stays uniform.
 * Beyond parsing it flags per-row {@code overflow} (more values than the
 * schema has fields) and {@code extra} (unknown keys) as {@code WARNING}s.
 */
@Service
public class RecordsKindHandler implements KindHandler {

    public static final String KIND = "records";

    @Override
    public String getName() {
        return KIND;
    }

    @Override
    public List<Finding> validate(String content, KindValidationContext ctx) {
        String target = StringUtils.isBlank(ctx.docPath()) ? KIND : ctx.docPath();
        String mime = effectiveMime(content, ctx.mimeType());

        RecordsDocument doc;
        try {
            doc = RecordsCodec.parse(content, mime);
        } catch (KindCodecException e) {
            return List.of(Finding.error(target, "records-parse", e.getMessage()));
        }

        List<Finding> findings = new ArrayList<>();
        if (doc.schema().isEmpty()) {
            findings.add(Finding.error(target, "records-schema-missing",
                    "`kind: records` requires a non-empty schema (field list)"));
            return findings;
        }

        int row = 0;
        for (RecordsItem item : doc.items()) {
            row++;
            String loc = target + " (row " + row + ")";
            if (!item.overflow().isEmpty()) {
                findings.add(Finding.warning(loc, "records-overflow",
                        "row has " + item.overflow().size() + " value(s) beyond the "
                                + doc.schema().size() + "-field schema"));
            }
            if (!item.extra().isEmpty()) {
                findings.add(Finding.warning(loc, "records-extra-field",
                        "row has unknown field(s): " + String.join(", ", item.extra().keySet())));
            }
        }
        return findings;
    }

    /**
     * Wire format for the codec: the context's mime when known, otherwise a
     * best-effort sniff of the content (JSON {@code {}/[]}, markdown front-matter
     * {@code ---}, else YAML).
     */
    private static String effectiveMime(String content, @Nullable String ctxMime) {
        if (!StringUtils.isBlank(ctxMime)) return ctxMime;
        String t = content.stripLeading();
        if (t.startsWith("{") || t.startsWith("[")) return "application/json";
        if (t.startsWith("---")) return "text/markdown";
        return "application/yaml";
    }
}
