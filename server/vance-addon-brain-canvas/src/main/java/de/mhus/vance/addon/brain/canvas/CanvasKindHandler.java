package de.mhus.vance.addon.brain.canvas;

import de.mhus.vance.addon.brain.canvas.model.CanvasDocument;
import de.mhus.vance.shared.document.kind.KindHandler;
import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationContext;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * {@link KindHandler} for the {@code canvas} kind (kind-handler track Phase 4).
 * Registers {@code canvas} as a known kind and wires its structural validation
 * into the generic {@code KindValidationService}, mirroring the
 * workpage→workbook pattern: validation delegates to the pure, content-based
 * {@link CanvasValidationService#validateGraph} (dangling edges, duplicate ids,
 * bad group references, …) so there is one canvas validator — {@code
 * canvas_validate} and {@code kind_validate} both flow through the same checks.
 *
 * <p>{@link CanvasValidationService} predates the shared validation vocabulary
 * and carries its own {@code Finding}/{@code Level}; this adapter maps those
 * onto the shared {@link Finding} the generic pipeline expects.
 */
@Service
public class CanvasKindHandler implements KindHandler {

    private static final String DEFAULT_MIME = "application/yaml";

    @Override
    public String getName() {
        return CanvasService.KIND;
    }

    @Override
    public List<Finding> validate(String content, KindValidationContext ctx) {
        String target = StringUtils.isBlank(ctx.docPath()) ? CanvasService.KIND : ctx.docPath();
        String mime = CanvasCodec.supports(ctx.mimeType()) ? ctx.mimeType() : DEFAULT_MIME;

        CanvasDocument canvas;
        try {
            canvas = CanvasCodec.parse(content, mime);
        } catch (RuntimeException e) {
            return List.of(Finding.error(target, "canvas-parse", "Parse error: " + e.getMessage()));
        }

        List<Finding> out = new ArrayList<>();
        for (CanvasValidationService.Finding f : CanvasValidationService.validateGraph(canvas)) {
            out.add(f.level() == CanvasValidationService.Level.ERROR
                    ? Finding.error(target, "canvas-graph", f.message())
                    : Finding.warning(target, "canvas-graph", f.message()));
        }
        return out;
    }
}
