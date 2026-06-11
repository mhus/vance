package de.mhus.vance.brain.tools.image;

import static de.mhus.vance.brain.tools.image.ImageToolArgs.errorResponse;
import static de.mhus.vance.brain.tools.image.ImageToolArgs.readNonBlank;
import static de.mhus.vance.brain.tools.image.ImageToolArgs.readString;
import static de.mhus.vance.brain.tools.image.ImageToolArgs.successResponse;

import de.mhus.vance.brain.image.AutoEnhanceRequest;
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
 * {@code image_auto_enhance} — the "magic wand". Classic histogram
 * percentile-clip + linear stretch + gamma + light saturation boost,
 * fully deterministic. Tuning sits in
 * {@code image.tools.auto_enhance.*} settings, not in this tool's
 * parameters.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ImageAutoEnhanceTool implements Tool {

    private final ImageManipulationService imageService;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "path", Map.of(
                            "type", "string",
                            "description", "Source image path."),
                    "targetPath", Map.of(
                            "type", "string",
                            "description",
                                    "Optional destination path; default = overwrite source.")),
            "required", List.of("path"));

    @Override public String name() { return "image_auto_enhance"; }

    @Override
    public String description() {
        return "Auto-enhance an image with the classic histogram-stretch + light gamma "
                + "+ saturation boost. Deterministic, no external API call, no parameters. "
                + "Good first try for flat / underexposed photos; for fine control use "
                + "`image_adjust` instead. Output preserves source MIME.";
    }

    @Override public boolean primary() { return true; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public Set<String> labels() { return Set.of("write"); }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx == null || ctx.tenantId() == null || ctx.tenantId().isBlank()) {
            throw new ToolException("image_auto_enhance requires a tenant scope");
        }
        AutoEnhanceRequest request = AutoEnhanceRequest.builder()
                .tenantId(ctx.tenantId())
                .userId(ctx.userId())
                .projectId(ctx.projectId())
                .processId(ctx.processId())
                .path(readNonBlank(params, "path"))
                .targetPath(readString(params, "targetPath"))
                .build();

        try {
            ImageOpResult result = imageService.autoEnhance(request);
            return successResponse(result);
        } catch (ImageManipulationException e) {
            log.info("image_auto_enhance failed: reason={} msg={}",
                    e.getReason(), e.getMessage());
            return errorResponse(e);
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage());
        }
    }
}
