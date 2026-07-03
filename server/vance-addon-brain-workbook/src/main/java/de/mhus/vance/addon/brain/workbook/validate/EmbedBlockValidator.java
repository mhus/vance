package de.mhus.vance.addon.brain.workbook.validate;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Validates a {@code vance-embed} fence: {@code uri} must resolve to an
 * existing document; when the reference carries a {@code ?kind=} hint, it must
 * match the target document's actual kind.
 */
@Component
public class EmbedBlockValidator implements BlockValidator {

    @Override
    public boolean supports(String fenceType) {
        return "embed".equals(fenceType);
    }

    @Override
    public List<Finding> validate(FenceBlock b, ValidationContext ctx) {
        List<Finding> out = new ArrayList<>();
        String raw = b.str("uri");
        if (raw == null) {
            out.add(Finding.error(b.location(), "missing-uri",
                    "`uri` is required but missing."));
            return out;
        }
        VanceRef ref = ctx.resolve(raw);
        if (ref == null) {
            out.add(Finding.error(b.location(), "bad-uri",
                    "`uri` is not a usable reference: '" + raw + "'."));
            return out;
        }
        if (!ctx.docs().exists(ref.path())) {
            out.add(Finding.error(b.location(), "unresolved-uri",
                    "`uri` points to a document that does not exist: '"
                            + ref.path() + "'."));
            return out;
        }
        if (ref.kind() != null) {
            String actual = ctx.docs().kindOf(ref.path());
            if (actual != null && !ref.kind().equals(actual)) {
                out.add(Finding.warning(b.location(), "kind-mismatch-uri",
                        "embed `?kind=" + ref.kind() + "` but '" + ref.path()
                                + "' is kind '" + actual + "'."));
            }
        }
        return out;
    }
}
