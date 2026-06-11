package de.mhus.vance.brain.tools.image;

import static de.mhus.vance.brain.tools.image.ImageToolArgs.errorResponse;
import static de.mhus.vance.brain.tools.image.ImageToolArgs.readInt;
import static de.mhus.vance.brain.tools.image.ImageToolArgs.readNonBlank;
import static de.mhus.vance.brain.tools.image.ImageToolArgs.readString;
import static de.mhus.vance.brain.tools.image.ImageToolArgs.successResponse;

import de.mhus.vance.brain.image.CropRequest;
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
 * {@code image_crop} — rectangular crop of an existing image document.
 * Wraps {@link ImageManipulationService#crop(CropRequest)} and turns
 * the {@link ImageOpResult} into the uniform success-shape from
 * {@code specification/image-manipulation.md} §4 (or the structured
 * failure-shape on {@link ImageManipulationException}).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ImageCropTool implements Tool {

    private final ImageManipulationService imageService;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "path", Map.of(
                            "type", "string",
                            "description",
                                    "Document path of the source image. Must point to "
                                            + "an existing PNG, JPEG, GIF or BMP."),
                    "targetPath", Map.of(
                            "type", "string",
                            "description",
                                    "Optional destination path. When omitted or equal "
                                            + "to `path` the source is overwritten and "
                                            + "the prior version is archived by "
                                            + "document-versioning."),
                    "x", Map.of(
                            "type", "integer",
                            "minimum", 0,
                            "description", "Left edge of the crop rectangle, 0-based."),
                    "y", Map.of(
                            "type", "integer",
                            "minimum", 0,
                            "description", "Top edge of the crop rectangle, 0-based."),
                    "width", Map.of(
                            "type", "integer",
                            "minimum", 1,
                            "description", "Crop width in pixels (must fit inside the image)."),
                    "height", Map.of(
                            "type", "integer",
                            "minimum", 1,
                            "description", "Crop height in pixels (must fit inside the image).")),
            "required", List.of("path", "x", "y", "width", "height"));

    @Override public String name() { return "image_crop"; }

    @Override
    public String description() {
        return "Crop a rectangular region out of an existing image document. "
                + "Coordinates are pixel-based with origin top-left. "
                + "Output preserves the source MIME (PNG/JPEG/GIF/BMP). "
                + "When `targetPath` is omitted the source is overwritten and "
                + "the prior version archived via document-versioning. "
                + "Synchronous, returns once the new bytes are committed.";
    }

    @Override public boolean primary() { return true; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public Set<String> labels() { return Set.of("write"); }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx == null || ctx.tenantId() == null || ctx.tenantId().isBlank()) {
            throw new ToolException("image_crop requires a tenant scope");
        }
        CropRequest request = CropRequest.builder()
                .tenantId(ctx.tenantId())
                .userId(ctx.userId())
                .projectId(ctx.projectId())
                .processId(ctx.processId())
                .path(readNonBlank(params, "path"))
                .targetPath(readString(params, "targetPath"))
                .x(readInt(params, "x"))
                .y(readInt(params, "y"))
                .width(readInt(params, "width"))
                .height(readInt(params, "height"))
                .build();

        try {
            ImageOpResult result = imageService.crop(request);
            return successResponse(result);
        } catch (ImageManipulationException e) {
            log.info("image_crop failed: reason={} msg={}",
                    e.getReason(), e.getMessage());
            return errorResponse(e);
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage());
        }
    }

}
