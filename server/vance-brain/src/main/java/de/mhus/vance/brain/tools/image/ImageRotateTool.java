package de.mhus.vance.brain.tools.image;

import static de.mhus.vance.brain.tools.image.ImageToolArgs.errorResponse;
import static de.mhus.vance.brain.tools.image.ImageToolArgs.readDouble;
import static de.mhus.vance.brain.tools.image.ImageToolArgs.readNonBlank;
import static de.mhus.vance.brain.tools.image.ImageToolArgs.readString;
import static de.mhus.vance.brain.tools.image.ImageToolArgs.successResponse;

import de.mhus.vance.brain.image.ImageManipulationException;
import de.mhus.vance.brain.image.ImageManipulationService;
import de.mhus.vance.brain.image.ImageOpResult;
import de.mhus.vance.brain.image.RotateRequest;
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
 * {@code image_rotate} — clockwise rotation by an arbitrary angle.
 * For 90° / 180° / 270° the result is identical to flipping with the
 * fast paths; for in-between angles the corners exposed by the rotation
 * are filled with {@code background}.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ImageRotateTool implements Tool {

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
                    "degrees", Map.of(
                            "type", "number",
                            "description",
                                    "Clockwise rotation in degrees. Negative = counter-clockwise. "
                                            + "Any real number; 90, 180, 270 are common."),
                    "background", Map.of(
                            "type", "string",
                            "description",
                                    "Hex color filling corners exposed by the rotation. "
                                            + "Default transparent on PNG/GIF, white on JPEG/BMP.")),
            "required", List.of("path", "degrees"));

    @Override public String name() { return "image_rotate"; }

    @Override
    public String description() {
        return "Rotate an image clockwise by `degrees` (any real number). "
                + "Corners exposed by the rotation are filled with `background` "
                + "(default: transparent for PNG/GIF, white for JPEG/BMP). "
                + "Output preserves source MIME.";
    }

    @Override public boolean primary() { return true; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public Set<String> labels() { return Set.of("write"); }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx == null || ctx.tenantId() == null || ctx.tenantId().isBlank()) {
            throw new ToolException("image_rotate requires a tenant scope");
        }
        RotateRequest request = RotateRequest.builder()
                .tenantId(ctx.tenantId())
                .userId(ctx.userId())
                .projectId(ctx.projectId())
                .processId(ctx.processId())
                .path(readNonBlank(params, "path"))
                .targetPath(readString(params, "targetPath"))
                .degrees(readDouble(params, "degrees"))
                .background(readString(params, "background"))
                .build();

        try {
            ImageOpResult result = imageService.rotate(request);
            return successResponse(result);
        } catch (ImageManipulationException e) {
            log.info("image_rotate failed: reason={} msg={}", e.getReason(), e.getMessage());
            return errorResponse(e);
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage());
        }
    }
}
