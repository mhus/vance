package de.mhus.vance.brain.tools.image;

import static de.mhus.vance.brain.tools.image.ImageToolArgs.errorResponse;
import static de.mhus.vance.brain.tools.image.ImageToolArgs.readNonBlank;
import static de.mhus.vance.brain.tools.image.ImageToolArgs.readOptionalInt;
import static de.mhus.vance.brain.tools.image.ImageToolArgs.readString;
import static de.mhus.vance.brain.tools.image.ImageToolArgs.successResponse;

import de.mhus.vance.brain.image.ImageManipulationException;
import de.mhus.vance.brain.image.ImageManipulationService;
import de.mhus.vance.brain.image.ImageOpResult;
import de.mhus.vance.brain.image.ResizeMode;
import de.mhus.vance.brain.image.ResizeRequest;
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
 * {@code image_resize} — resize an existing image. Five modes
 * ({@code exact}, {@code width}, {@code height}, {@code cover},
 * {@code contain}) — see {@code specification/image-manipulation.md} §4.2.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ImageResizeTool implements Tool {

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
                    "mode", Map.of(
                            "type", "string",
                            "enum", List.of("exact", "width", "height", "cover", "contain"),
                            "description",
                                    "Resize strategy. `exact` distorts if aspect-ratio differs. "
                                            + "`width`/`height` scale proportionally. `cover` fills "
                                            + "and crops excess. `contain` fits and pads with "
                                            + "`background`. Default: exact."),
                    "width", Map.of(
                            "type", "integer", "minimum", 1,
                            "description",
                                    "Target width (required for exact, width, cover, contain)."),
                    "height", Map.of(
                            "type", "integer", "minimum", 1,
                            "description",
                                    "Target height (required for exact, height, cover, contain)."),
                    "background", Map.of(
                            "type", "string",
                            "description",
                                    "Padding color for `contain`. Hex `#rrggbb` or `#aarrggbb`. "
                                            + "Default transparent on PNG/GIF, white on JPEG/BMP.")),
            "required", List.of("path"));

    @Override public String name() { return "image_resize"; }

    @Override
    public String description() {
        return "Resize an image. Choose `mode`: `exact` (forces dimensions, may distort), "
                + "`width` / `height` (proportional), `cover` (fills, crops excess), "
                + "`contain` (fits, pads with `background`). Output preserves source MIME. "
                + "Overwrites source unless `targetPath` is given.";
    }

    @Override public boolean primary() { return true; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public Set<String> labels() { return Set.of("write"); }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx == null || ctx.tenantId() == null || ctx.tenantId().isBlank()) {
            throw new ToolException("image_resize requires a tenant scope");
        }
        ResizeRequest request = ResizeRequest.builder()
                .tenantId(ctx.tenantId())
                .userId(ctx.userId())
                .projectId(ctx.projectId())
                .processId(ctx.processId())
                .path(readNonBlank(params, "path"))
                .targetPath(readString(params, "targetPath"))
                .mode(ResizeMode.fromWire(readString(params, "mode")))
                .width(readOptionalInt(params, "width"))
                .height(readOptionalInt(params, "height"))
                .background(readString(params, "background"))
                .build();

        try {
            ImageOpResult result = imageService.resize(request);
            return successResponse(result);
        } catch (ImageManipulationException e) {
            log.info("image_resize failed: reason={} msg={}", e.getReason(), e.getMessage());
            return errorResponse(e);
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage());
        }
    }
}
