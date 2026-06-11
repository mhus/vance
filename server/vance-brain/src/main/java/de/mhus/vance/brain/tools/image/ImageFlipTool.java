package de.mhus.vance.brain.tools.image;

import static de.mhus.vance.brain.tools.image.ImageToolArgs.errorResponse;
import static de.mhus.vance.brain.tools.image.ImageToolArgs.readNonBlank;
import static de.mhus.vance.brain.tools.image.ImageToolArgs.readString;
import static de.mhus.vance.brain.tools.image.ImageToolArgs.successResponse;

import de.mhus.vance.brain.image.FlipAxis;
import de.mhus.vance.brain.image.FlipRequest;
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
 * {@code image_flip} — mirror an image along the horizontal
 * (left-right) or vertical (top-bottom) axis.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ImageFlipTool implements Tool {

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
                    "axis", Map.of(
                            "type", "string",
                            "enum", List.of("horizontal", "vertical"),
                            "description",
                                    "Mirror axis: `horizontal` flips left-right, "
                                            + "`vertical` flips top-bottom.")),
            "required", List.of("path", "axis"));

    @Override public String name() { return "image_flip"; }

    @Override
    public String description() {
        return "Mirror an image. `axis: horizontal` flips left-right; "
                + "`axis: vertical` flips top-bottom. Output preserves source MIME.";
    }

    @Override public boolean primary() { return true; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public Set<String> labels() { return Set.of("write"); }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx == null || ctx.tenantId() == null || ctx.tenantId().isBlank()) {
            throw new ToolException("image_flip requires a tenant scope");
        }
        FlipRequest request = FlipRequest.builder()
                .tenantId(ctx.tenantId())
                .userId(ctx.userId())
                .projectId(ctx.projectId())
                .processId(ctx.processId())
                .path(readNonBlank(params, "path"))
                .targetPath(readString(params, "targetPath"))
                .axis(FlipAxis.fromWire(readString(params, "axis")))
                .build();

        try {
            ImageOpResult result = imageService.flip(request);
            return successResponse(result);
        } catch (ImageManipulationException e) {
            log.info("image_flip failed: reason={} msg={}", e.getReason(), e.getMessage());
            return errorResponse(e);
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage());
        }
    }
}
