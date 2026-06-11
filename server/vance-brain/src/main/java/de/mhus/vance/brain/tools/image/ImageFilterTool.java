package de.mhus.vance.brain.tools.image;

import static de.mhus.vance.brain.tools.image.ImageToolArgs.errorResponse;
import static de.mhus.vance.brain.tools.image.ImageToolArgs.readMap;
import static de.mhus.vance.brain.tools.image.ImageToolArgs.readNonBlank;
import static de.mhus.vance.brain.tools.image.ImageToolArgs.readString;
import static de.mhus.vance.brain.tools.image.ImageToolArgs.successResponse;

import de.mhus.vance.brain.image.FilterName;
import de.mhus.vance.brain.image.FilterRequest;
import de.mhus.vance.brain.image.ImageManipulationException;
import de.mhus.vance.brain.image.ImageManipulationService;
import de.mhus.vance.brain.image.ImageOpResult;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@code image_filter} — single dispatcher for effect filters. The
 * {@code filter} parameter chooses the effect (e.g. {@code blur_gaussian},
 * {@code grayscale}, {@code sepia}); {@code params} carries per-filter
 * knobs. See {@code specification/image-manipulation.md} §4.6 for the
 * catalogue.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ImageFilterTool implements Tool {

    private final ImageManipulationService imageService;

    private static final List<String> FILTER_NAMES = List.of(
            "blur_gaussian", "sharpen", "grayscale", "sepia", "invert",
            "edge", "emboss", "posterize", "solarize", "threshold");

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "path", Map.of(
                            "type", "string",
                            "description", "Source image path."),
                    "targetPath", Map.of(
                            "type", "string",
                            "description", "Optional destination path; default = overwrite source."),
                    "filter", Map.of(
                            "type", "string",
                            "enum", FILTER_NAMES,
                            "description",
                                    "Effect to apply. See `params` table for per-filter knobs."),
                    "params", Map.of(
                            "type", "object",
                            "description",
                                    "Filter-specific parameters. `blur_gaussian`: `radius` "
                                            + "(int 1-50, default 5). `posterize`: `levels` "
                                            + "(int 2-8, default 4). `threshold`: `threshold` "
                                            + "(int 0-255, default 128). All other filters take "
                                            + "no params.")),
            "required", List.of("path", "filter"));

    @Override public String name() { return "image_filter"; }

    @Override
    public String description() {
        return "Apply one named effect filter to an image. Filters available: "
                + "blur_gaussian (radius 1-50), sharpen, grayscale, sepia, invert, edge, "
                + "emboss, posterize (levels 2-8), solarize, threshold (0-255). "
                + "Use `params` for the knob (e.g. `{filter: 'blur_gaussian', params: "
                + "{radius: 12}}`). Output preserves source MIME.";
    }

    @Override public boolean primary() { return true; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public Set<String> labels() { return Set.of("write"); }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx == null || ctx.tenantId() == null || ctx.tenantId().isBlank()) {
            throw new ToolException("image_filter requires a tenant scope");
        }
        Map<String, Object> filterParams = new LinkedHashMap<>(
                readMap(params, "params"));
        FilterRequest.FilterRequestBuilder builder = FilterRequest.builder()
                .tenantId(ctx.tenantId())
                .userId(ctx.userId())
                .projectId(ctx.projectId())
                .processId(ctx.processId())
                .path(readNonBlank(params, "path"))
                .targetPath(readString(params, "targetPath"))
                .filter(FilterName.fromWire(readString(params, "filter")));
        filterParams.forEach(builder::param);

        try {
            ImageOpResult result = imageService.filter(builder.build());
            return successResponse(result);
        } catch (ImageManipulationException e) {
            log.info("image_filter failed: reason={} msg={}", e.getReason(), e.getMessage());
            return errorResponse(e);
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage());
        }
    }
}
