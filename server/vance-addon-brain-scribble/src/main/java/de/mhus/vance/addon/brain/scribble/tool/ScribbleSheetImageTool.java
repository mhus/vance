package de.mhus.vance.addon.brain.scribble.tool;

import de.mhus.vance.addon.brain.scribble.ScribbleRenderer;
import de.mhus.vance.addon.brain.scribble.ScribbleRenderer.Region;
import de.mhus.vance.addon.brain.scribble.ScribbleService;
import de.mhus.vance.addon.brain.scribble.model.ScribbleSheet;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiConfigScope;
import de.mhus.vance.brain.ai.AiModelResolver;
import de.mhus.vance.brain.ai.ChatBehavior;
import de.mhus.vance.brain.ai.ChatBehaviorBuilder;
import de.mhus.vance.brain.ai.ModelCapability;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.ModelInfo;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Renders a {@code kind: scribble} sheet to PNG and hands it to the model as
 * an image attachment for its next turn — the way a vision-capable model
 * "reads" handwriting. The image block in the result is lifted out by the
 * brain's {@code ToolImageHarvester} (stored in {@code _chatbox}, result
 * rewritten to a short reference), and the engine delivers it as a real
 * {@code ImageContent} block (see {@code planning/scribble.md} §16).
 *
 * <p>Vision pre-check: without it, a non-vision model would get the
 * rewritten "you will see the image" promise and then never see it — the
 * engine's attachment append fails silently (logged, not shown). This tool
 * resolves the calling process's model (same chain the engine uses:
 * {@link ChatBehaviorBuilder#fromProcess} + {@link ModelCatalog}) and
 * answers friendly and early instead. The pre-check fails open — any
 * resolution problem delivers normally, and the compose path stays the
 * authoritative gate.
 */
@Component
public class ScribbleSheetImageTool implements Tool {

    /** Upper render bound — 600 dpi is 4× the sheet raster; beyond that is waste. */
    static final int DPI_MAX = 600;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type",
            "object",
            "properties",
            new LinkedHashMap<String, Object>() {
                {
                    put("path", Map.of("type", "string", "description", "Scribble document path to render."));
                    put("projectId", Map.of("type", "string"));
                    put(
                            "dpi",
                            Map.of(
                                    "type",
                                    "integer",
                                    "description",
                                    "Render resolution, default 150 (the sheet's native "
                                            + "dpi). Higher only for very fine handwriting; "
                                            + "capped at "
                                            + DPI_MAX
                                            + "."));
                    put(
                            "region",
                            Map.of(
                                    "type",
                                    "object",
                                    "description",
                                    "Optional crop in sheet coordinates " + "{x, y, w, h}. Default: the whole sheet.",
                                    "properties",
                                    new LinkedHashMap<String, Object>() {
                                        {
                                            put("x", Map.of("type", "integer"));
                                            put("y", Map.of("type", "integer"));
                                            put("w", Map.of("type", "integer"));
                                            put("h", Map.of("type", "integer"));
                                        }
                                    },
                                    "required",
                                    List.of("x", "y", "w", "h")));
                }
            },
            "required",
            List.of("path"));

    private final EddieContext eddieContext;
    private final DocumentService documentService;
    private final ScribbleService scribbleService;
    private final ThinkProcessService thinkProcessService;
    private final SettingService settingService;
    private final AiModelResolver aiModelResolver;
    private final ModelCatalog modelCatalog;

    public ScribbleSheetImageTool(
            EddieContext eddieContext,
            DocumentService documentService,
            ScribbleService scribbleService,
            ThinkProcessService thinkProcessService,
            SettingService settingService,
            AiModelResolver aiModelResolver,
            ModelCatalog modelCatalog) {
        this.eddieContext = eddieContext;
        this.documentService = documentService;
        this.scribbleService = scribbleService;
        this.thinkProcessService = thinkProcessService;
        this.settingService = settingService;
        this.aiModelResolver = aiModelResolver;
        this.modelCatalog = modelCatalog;
    }

    @Override
    public String name() {
        return "scribble_sheet_image";
    }

    @Override
    public String description() {
        return "Render a handwriting sheet (kind: scribble) to a PNG image and attach "
                + "it to your next turn, so you can look at the handwriting if your "
                + "model has vision. Only useful with a vision-capable model — the tool "
                + "checks and tells you otherwise. Optional dpi (default 150) and an "
                + "optional region crop in sheet coordinates. Read-only.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public Set<String> labels() {
        return Set.of("eddie", "read", "document", "scribble");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        ScribbleToolSupport.Resolved resolved =
                ScribbleToolSupport.resolveByPath(eddieContext, documentService, params, ctx);
        DocumentDocument doc = resolved.doc();
        ScribbleSheet sheet = scribbleService.readSheet(doc);

        int requestedDpi = paramInt(params, "dpi", ScribbleRenderer.NATIVE_DPI);
        int dpi = Math.min(DPI_MAX, Math.max(1, requestedDpi));
        Region region = paramRegion(params);

        if (sheet.strokes().isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("path", doc.getPath());
            result.put("note", "Sheet has no strokes — nothing to render.");
            return result;
        }

        Vision vision = visionCheck(ctx);
        if (!vision.ok) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", false);
            result.put(
                    "reason",
                    "This process's model '" + vision.model + "' has no vision capability — "
                            + "it cannot look at images, so rendering the sheet would be wasted. "
                            + "Switch to a vision-capable model to read handwriting.");
            result.put("path", doc.getPath());
            return result;
        }

        byte[] png;
        try {
            png = ScribbleRenderer.renderPng(sheet, dpi, region);
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage(), e);
        } catch (Exception e) {
            throw new ToolException("Could not render scribble '" + doc.getPath() + "': " + e.getMessage(), e);
        }

        int imgW = region != null ? region.w() : sheet.size().w();
        int imgH = region != null ? region.h() : sheet.size().h();

        Map<String, Object> imageBlock = new LinkedHashMap<>();
        imageBlock.put("type", "image");
        imageBlock.put("mimeType", "image/png");
        imageBlock.put("data", Base64.getEncoder().encodeToString(png));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("path", doc.getPath());
        result.put("dpi", dpi);
        if (requestedDpi > DPI_MAX) {
            result.put("note", "Requested dpi " + requestedDpi + " was capped at " + DPI_MAX + ".");
        }
        result.put("width", imgW);
        result.put("height", imgH);
        result.put("strokeCount", sheet.strokes().size());
        result.put("content", List.of(imageBlock));
        return result;
    }

    // ── vision pre-check ──────────────────────────────────────────

    private record Vision(boolean ok, @Nullable String model) {

        static Vision deliver() {
            return new Vision(true, null);
        }
    }

    /**
     * Resolves the calling process's model and checks {@link ModelCapability#VISION}.
     * No process id, unknown process, or any resolution failure → deliver: the
     * engine's message composer remains the authoritative, fail-closed gate.
     */
    private Vision visionCheck(ToolInvocationContext ctx) {
        if (ctx.processId() == null) return Vision.deliver();
        try {
            ThinkProcessDocument process =
                    thinkProcessService.findById(ctx.processId()).orElse(null);
            if (process == null) return Vision.deliver();
            ChatBehavior behavior = ChatBehaviorBuilder.fromProcess(process, settingService, aiModelResolver);
            AiChatConfig primary = behavior.entries().get(0).config();
            // Tenant-pinned recipes resolve their whole endpoint from the _tenant
            // layer — the catalog must be read with the same collapsed view.
            String projectId = ChatBehaviorBuilder.readAiConfigScope(process) == AiConfigScope.TENANT
                    ? null
                    : process.getProjectId();
            ModelInfo info = modelCatalog.lookupOrDefault(
                    process.getTenantId(),
                    projectId,
                    primary.providerInstance(),
                    primary.provider(),
                    primary.modelName());
            return new Vision(
                    info.capabilities().contains(ModelCapability.VISION),
                    primary.provider() + ":" + primary.modelName());
        } catch (RuntimeException e) {
            return Vision.deliver();
        }
    }

    // ── param coercion ────────────────────────────────────────────

    private static int paramInt(Map<String, Object> params, String key, int fallback) {
        Object v = params.get(key);
        return v instanceof Number n ? n.intValue() : fallback;
    }

    private static @Nullable Region paramRegion(Map<String, Object> params) {
        Object v = params.get("region");
        if (!(v instanceof Map<?, ?> m)) return null;
        try {
            int x = ((Number) m.get("x")).intValue();
            int y = ((Number) m.get("y")).intValue();
            int w = ((Number) m.get("w")).intValue();
            int h = ((Number) m.get("h")).intValue();
            return new ScribbleRenderer.Region(x, y, w, h);
        } catch (RuntimeException e) {
            throw new ToolException("region must be {x, y, w, h} in sheet coordinates", e);
        }
    }
}
