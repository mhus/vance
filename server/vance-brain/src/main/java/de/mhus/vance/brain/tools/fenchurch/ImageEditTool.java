package de.mhus.vance.brain.tools.fenchurch;

import de.mhus.vance.brain.fenchurch.FenchurchException;
import de.mhus.vance.brain.fenchurch.FenchurchService;
import de.mhus.vance.brain.fenchurch.GenerateImageRequest;
import de.mhus.vance.brain.fenchurch.GenerateImageResult;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The {@code image_edit} tool — image-to-image generation: prompt plus
 * one or more reference images. Wraps
 * {@link FenchurchService#editImage}; the wire is the same as
 * {@code image_generate} plus a {@code referenceDocumentIds} list.
 *
 * <p>Reference ids are document ids in the caller's project — the same
 * ids the chat attachments carry ({@code AttachmentRef.documentId}),
 * so an image the user attached to the turn is directly editable:
 * resolve → edit with that id → done. Resolution is scope-checked
 * server-side (a foreign project id fails), MIME-checked (only
 * images) and size-capped through the shared attachment pipeline.
 *
 * <p>The model must support editing — the catalog's
 * {@code maxInputReferences} flag gates it ({@code invalid_choice}
 * error names the limit otherwise). The alias defaults to
 * {@code default:image} like generate; use {@code default:image-high}
 * for the quality tier.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ImageEditTool implements Tool {

    private final FenchurchService fenchurchService;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties",
                    Map.of(
                            "prompt",
                                    Map.of(
                                            "type",
                                            "string",
                                            "description",
                                            "What to change / render. Required, non-empty. Describe"
                                                    + " the edit, not the whole scene: 'make this a"
                                                    + " watercolor', 'replace the background with a"
                                                    + " mountain range'. Style tokens go inline here."),
                            "referenceDocumentIds",
                                    Map.of(
                                            "type",
                                            "array",
                                            "items",
                                            Map.of("type", "string"),
                                            "description",
                                            "Document ids of the reference image(s), in priority"
                                                    + " order — the first is the primary subject. Required,"
                                                    + " at least one. These are the same ids chat"
                                                    + " attachments use; doc_read / image_generate"
                                                    + " results carry them too."),
                            "path",
                                    Map.of(
                                            "type",
                                            "string",
                                            "description",
                                            "Optional document path for the result. If set,"
                                                    + " overwrites an existing image at that path;"
                                                    + " otherwise the file lands at"
                                                    + " images/<uuid>-<slug>.png."),
                            "title",
                                    Map.of(
                                            "type",
                                            "string",
                                            "description",
                                            "Optional title override. When absent, a short"
                                                    + " title is generated from the prompt."),
                            "aspectRatio",
                                    Map.of(
                                            "type", "string",
                                            "enum", List.of("1:1", "16:9", "9:16", "4:3", "3:4"),
                                            "description", "Result aspect ratio. Defaults to 1:1."),
                            "alias",
                                    Map.of(
                                            "type",
                                            "string",
                                            "description",
                                            "Optional model alias to resolve."
                                                    + " Defaults to default:image;"
                                                    + " use default:image-high for the quality tier.")),
            "required", List.of("prompt", "referenceDocumentIds"));

    @Override
    public String name() {
        return "image_edit";
    }

    @Override
    public String description() {
        return "Edit or transform existing image(s): generate one image from a"
                + " prompt PLUS reference images (style transfer, restyling,"
                + " variation, 'make it look like X'). References are document"
                + " ids — the same ids chat attachments and image_generate"
                + " results carry. The resolved model must support image"
                + " editing (the catalog's maxInputReferences flag); an"
                + " unsupported model returns invalid_choice naming the"
                + " problem. Writes the result to the document store and"
                + " returns the path. Synchronous; for bulk edits use a"
                + " Marvin plan with one child per image.";
    }

    @Override
    public boolean primary() {
        return true;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Set<String> labels() {
        return Set.of(ToolLabels.WORKER, "write");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx == null || ctx.tenantId() == null || ctx.tenantId().isBlank()) {
            throw new ToolException("image_edit requires a tenant scope");
        }
        String prompt = readNonBlank(params, "prompt");
        List<String> referenceIds = readStringList(params, "referenceDocumentIds");
        if (referenceIds.isEmpty()) {
            throw new ToolException("'referenceDocumentIds' must contain at least one document id");
        }

        GenerateImageRequest request = GenerateImageRequest.builder()
                .tenantId(ctx.tenantId())
                .projectId(ctx.projectId())
                .processId(ctx.processId())
                .userId(ctx.userId())
                .prompt(prompt)
                .referenceDocumentIds(referenceIds)
                .path(readString(params, "path"))
                .title(readString(params, "title"))
                .aspectRatio(readString(params, "aspectRatio"))
                .alias(readString(params, "alias"))
                .build();

        try {
            GenerateImageResult result = fenchurchService.editImage(request);
            return successResponse(result);
        } catch (FenchurchException e) {
            log.info("image_edit failed: reason={} msg={}", e.getReason(), e.getMessage());
            return errorResponse(e);
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage(), e);
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

    private static List<String> readStringList(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(o -> o instanceof String s && !s.isBlank())
                .map(o -> ((String) o).trim())
                .toList();
    }
}
