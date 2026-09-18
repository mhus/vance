package de.mhus.vance.addon.brain.scribble;

import de.mhus.vance.shared.document.kind.KindHandler;
import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationContext;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * {@link KindHandler} for the {@code scribble} kind. Registers
 * {@code scribble} as a known kind and wires the structural validation of
 * {@link ScribbleValidationService} into the generic
 * {@code KindValidationService} — one scribble validator, reached by
 * {@code kind_validate} and later by {@code scribble_validate}.
 */
@Service
public class ScribbleKindHandler implements KindHandler {

    private static final String DEFAULT_MIME = "application/yaml";

    @Override
    public String getName() {
        return ScribbleService.KIND;
    }

    @Override
    public List<Finding> validate(String content, KindValidationContext ctx) {
        String target = StringUtils.isBlank(ctx.docPath()) ? ScribbleService.KIND : ctx.docPath();
        String mime = ScribbleCodec.supports(ctx.mimeType()) ? ctx.mimeType() : DEFAULT_MIME;
        try {
            return ScribbleValidationService.validate(content, mime, target);
        } catch (RuntimeException e) {
            return List.of(Finding.error(target, "scribble-parse", "Parse error: " + e.getMessage()));
        }
    }
}
