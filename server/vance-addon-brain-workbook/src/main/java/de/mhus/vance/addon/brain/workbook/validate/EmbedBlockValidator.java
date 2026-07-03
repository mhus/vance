package de.mhus.vance.addon.brain.workbook.validate;

import de.mhus.vance.addon.brain.workpage.Block;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Validates a {@link Block.Embed}: {@code uri} must resolve to an existing
 * document; when the reference carries a {@code ?kind=} hint, it must match
 * the target document's actual kind.
 */
@Component
public class EmbedBlockValidator implements BlockValidator {

    @Override
    public boolean supports(Block block) {
        return block instanceof Block.Embed;
    }

    @Override
    public List<Finding> validate(Block block, ValidationContext ctx) {
        Block.Embed embed = (Block.Embed) block;
        List<Finding> out = new ArrayList<>();
        String raw = embed.uri();
        if (raw == null || raw.isBlank()) {
            out.add(Finding.error(ctx.location(), "missing-uri",
                    "`uri` is required but missing."));
            return out;
        }
        VanceRef ref = ctx.resolve(raw);
        if (ref == null) {
            out.add(Finding.error(ctx.location(), "bad-uri",
                    "`uri` is not a usable reference: '" + raw + "'."));
            return out;
        }
        if (!ctx.docs().exists(ref.path())) {
            out.add(Finding.error(ctx.location(), "unresolved-uri",
                    "`uri` points to a document that does not exist: '"
                            + ref.path() + "'."));
            return out;
        }
        if (ref.kind() != null) {
            String actual = ctx.docs().kindOf(ref.path());
            if (actual != null && !ref.kind().equals(actual)) {
                out.add(Finding.warning(ctx.location(), "kind-mismatch-uri",
                        "embed `?kind=" + ref.kind() + "` but '" + ref.path()
                                + "' is kind '" + actual + "'."));
            }
        }
        return out;
    }
}
