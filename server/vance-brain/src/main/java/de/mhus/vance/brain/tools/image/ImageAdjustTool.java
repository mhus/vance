package de.mhus.vance.brain.tools.image;

import static de.mhus.vance.brain.tools.image.ImageToolArgs.errorResponse;
import static de.mhus.vance.brain.tools.image.ImageToolArgs.readNonBlank;
import static de.mhus.vance.brain.tools.image.ImageToolArgs.readOptionalDouble;
import static de.mhus.vance.brain.tools.image.ImageToolArgs.readString;
import static de.mhus.vance.brain.tools.image.ImageToolArgs.successResponse;

import de.mhus.vance.brain.image.AdjustRequest;
import de.mhus.vance.brain.image.ImageManipulationException;
import de.mhus.vance.brain.image.ImageManipulationService;
import de.mhus.vance.brain.image.ImageOpResult;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@code image_adjust} — combined brightness / contrast / saturation /
 * gamma adjustment. Only the fields the caller sets are applied; the
 * fixed order is {@code gamma → brightness → contrast → saturation}.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ImageAdjustTool implements Tool {

    private final ImageManipulationService imageService;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "path", Map.of(
                            "type", "string",
                            "description", "Source image path."),
                    "targetPath", Map.of(
                            "type", "string",
                            "description", "Optional destination path; default = overwrite source."),
                    "brightness", Map.of(
                            "type", "number", "minimum", -1.0, "maximum", 1.0,
                            "description",
                                    "Brightness delta in [-1.0, 1.0]. 0 = neutral, "
                                            + "+0.2 ≈ noticeably brighter."),
                    "contrast", Map.of(
                            "type", "number", "minimum", -1.0, "maximum", 1.0,
                            "description", "Contrast delta in [-1.0, 1.0]. 0 = neutral."),
                    "saturation", Map.of(
                            "type", "number", "minimum", -1.0, "maximum", 1.0,
                            "description",
                                    "Saturation delta in [-1.0, 1.0]. -1 = grayscale, "
                                            + "0 = neutral, +1 = vivid."),
                    "gamma", Map.of(
                            "type", "number", "minimum", 0.1, "maximum", 5.0,
                            "description",
                                    "Gamma factor in [0.1, 5.0]. 1.0 = neutral. "
                                            + "Below 1 brightens midtones; above 1 darkens them.")),
            "required", List.of("path"));

    @Override public String name() { return "image_adjust"; }

    @Override
    public String description() {
        return "Adjust brightness, contrast, saturation, and/or gamma. Each parameter is "
                + "optional but at least one must be set. Apply order is fixed "
                + "(gamma → brightness → contrast → saturation). Output preserves source MIME. "
                + "For a one-call \"fix this flat photo\" pass, prefer `image_auto_enhance` instead.";
    }

    @Override public boolean primary() { return true; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public Set<String> labels() { return Set.of("write"); }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx == null || ctx.tenantId() == null || ctx.tenantId().isBlank()) {
            throw new ToolException("image_adjust requires a tenant scope");
        }
        AdjustRequest request = AdjustRequest.builder()
                .tenantId(ctx.tenantId())
                .userId(ctx.userId())
                .projectId(ctx.projectId())
                .processId(ctx.processId())
                .path(readNonBlank(params, "path"))
                .targetPath(readString(params, "targetPath"))
                .brightness(readOptionalDouble(params, "brightness"))
                .contrast(readOptionalDouble(params, "contrast"))
                .saturation(readOptionalDouble(params, "saturation"))
                .gamma(readOptionalDouble(params, "gamma"))
                .build();

        try {
            ImageOpResult result = imageService.adjust(request);
            return successResponse(result);
        } catch (ImageManipulationException e) {
            log.info("image_adjust failed: reason={} msg={}", e.getReason(), e.getMessage());
            return errorResponse(e);
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage());
        }
    }
}
