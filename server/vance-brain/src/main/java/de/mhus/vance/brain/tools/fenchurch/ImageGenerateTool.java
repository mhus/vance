package de.mhus.vance.brain.tools.fenchurch;

import de.mhus.vance.brain.fenchurch.FenchurchException;
import de.mhus.vance.brain.fenchurch.FenchurchService;
import de.mhus.vance.brain.fenchurch.GenerateImageRequest;
import de.mhus.vance.brain.fenchurch.GenerateImageResult;
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
 * The {@code image_generate} tool — wraps {@link FenchurchService}.
 * Returns either a success object ({@code path}, {@code mimeType},
 * {@code sizeBytes}, {@code modelUsed}, {@code durationMs}, {@code title})
 * or a structured failure ({@code error}, {@code message},
 * {@code retryable}). See {@code planning/fenchurch-service.md} §4.1.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ImageGenerateTool implements Tool {

    private final FenchurchService fenchurchService;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "prompt", Map.of(
                            "type", "string",
                            "description",
                                    "Image-generation prompt. Required, non-empty. "
                                            + "Style tokens (medium, lighting, "
                                            + "perspective, …) can be included "
                                            + "inline, but persistent styles should "
                                            + "live in the style layer — use "
                                            + "`image_style_set` instead."),
                    "path", Map.of(
                            "type", "string",
                            "description",
                                    "Optional document path. If set, overwrites an "
                                            + "existing image at that path; otherwise "
                                            + "the file is written to "
                                            + "`images/<uuid>-<slug>.png` with the "
                                            + "slug derived from the prompt."),
                    "title", Map.of(
                            "type", "string",
                            "description",
                                    "Optional human-readable title override. When "
                                            + "absent, a short title is generated "
                                            + "from the prompt."),
                    "aspectRatio", Map.of(
                            "type", "string",
                            "enum", List.of("1:1", "16:9", "9:16", "4:3", "3:4"),
                            "description",
                                    "Image aspect ratio. Defaults to 1:1.")),
            "required", List.of("prompt"));

    @Override public String name() { return "image_generate"; }

    @Override
    public String description() {
        return "Generate one image from a text prompt via the Fenchurch "
                + "image-generation service. Writes the bytes to the document "
                + "store and returns the path so the worker can render the "
                + "image with Markdown (`![alt](path)`). Synchronous — a single "
                + "call can take from a few seconds (fast model) up to several "
                + "minutes (quality model); plan accordingly and do NOT loop. "
                + "For bulk generation use a Marvin plan with one child per "
                + "image. For persistent style ('medieval', 'transparent "
                + "background'), prefer `image_style_set` over baking it into "
                + "every prompt.";
    }

    @Override public boolean primary() { return true; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public Set<String> labels() { return Set.of("write"); }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx == null || ctx.tenantId() == null || ctx.tenantId().isBlank()) {
            throw new ToolException("image_generate requires a tenant scope");
        }
        String prompt = readNonBlank(params, "prompt");

        GenerateImageRequest request = GenerateImageRequest.builder()
                .tenantId(ctx.tenantId())
                .projectId(ctx.projectId())
                .processId(ctx.processId())
                .userId(ctx.userId())
                .prompt(prompt)
                .path(readString(params, "path"))
                .title(readString(params, "title"))
                .aspectRatio(readString(params, "aspectRatio"))
                .build();

        try {
            GenerateImageResult result = fenchurchService.generate(request);
            return successResponse(result);
        } catch (FenchurchException e) {
            log.info("image_generate failed: reason={} msg={}",
                    e.getReason(), e.getMessage());
            return errorResponse(e);
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage());
        }
    }

    private static Map<String, Object> successResponse(GenerateImageResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", r.getPath());
        out.put("mimeType", r.getMimeType());
        out.put("sizeBytes", r.getSizeBytes());
        out.put("modelUsed", r.getModelUsed());
        out.put("durationMs", r.getDurationMs());
        if (r.getTitle() != null) {
            out.put("title", r.getTitle());
        }
        return out;
    }

    private static Map<String, Object> errorResponse(FenchurchException e) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", e.getReason().wire());
        out.put("message", e.getMessage());
        out.put("retryable", e.getReason().retryable());
        return out;
    }

    private static String readNonBlank(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required");
        }
        return s.trim();
    }

    private static String readString(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (raw == null) return null;
        String s = raw.toString().trim();
        return s.isBlank() ? null : s;
    }
}
