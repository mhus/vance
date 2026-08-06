package de.mhus.vance.brain.ai.attachment;

import de.mhus.vance.api.attachment.AttachmentRef;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Lifts image content out of a tool result and turns it into a project
 * document the model can actually look at.
 *
 * <p>MCP tools return images the way the MCP spec prescribes — a
 * {@code content} array holding {@code {"type":"image","data":"<base64>",
 * "mimeType":"image/png"}}. Vance hands tool results back to the model as
 * JSON <b>text</b>, so without this step a screenshot arrives as a
 * five-figure base64 blob: unreadable for the model, and past
 * {@link de.mhus.vance.brain.tools.ToolResultStorage}'s threshold it is
 * replaced by a stub, so even the blob does not survive.
 *
 * <p>What happens instead: the bytes become a document, the block in the
 * result is rewritten to a short reference, and the caller receives an
 * {@link AttachmentRef} to feed into the next LLM call as a real image
 * block (see {@link AttachedUserMessageComposer}). The result the model
 * reads therefore stays small and says where the picture went.
 *
 * <p>Deliberately no work in {@code vance-toolpack}: that module must not
 * depend on {@code DocumentService} (dependency rule), and it does not
 * need to — the MCP wire format is already structured, so the harvest
 * happens brain-side where documents exist.
 */
@Component
@Slf4j
public class ToolImageHarvester {

    /** MCP content-block discriminator for images. */
    private static final String TYPE_IMAGE = "image";

    /** Where harvested images land — same folder the web composer uploads to. */
    static final String FOLDER = "_chatbox";

    private static final String DEFAULT_MIME = "image/png";

    /**
     * Guard against a single tool call filling the document pool. A
     * screenshot batch of three is plausible; thirty is a runaway loop.
     */
    static final int MAX_IMAGES_PER_RESULT = 4;

    private final DocumentService documentService;
    private final SecurityContextFactory contextFactory;
    private final long maxBytesPerImage;

    public ToolImageHarvester(
            DocumentService documentService,
            SecurityContextFactory contextFactory,
            @Value("${vance.ai.attachment.max-bytes-per-file:20971520}") long maxBytesPerImage) {
        this.documentService = documentService;
        this.contextFactory = contextFactory;
        this.maxBytesPerImage = maxBytesPerImage;
    }

    /** Rewritten result plus the refs to show the model next turn. */
    public record Harvest(Map<String, Object> result, List<AttachmentRef> attachments) {

        public boolean isEmpty() {
            return attachments.isEmpty();
        }
    }

    /**
     * Scans {@code result} for image content blocks. Returns the very
     * same map and an empty ref list when there is nothing to harvest —
     * the overwhelmingly common case, so the check must stay cheap.
     *
     * <p>Never throws: a tool call that produced a usable result must not
     * fail because the picture could not be stored. A failed harvest
     * leaves that block untouched and says so in the log.
     */
    @SuppressWarnings("unchecked")
    public Harvest harvest(
            Map<String, Object> result, String tenantId, String projectId,
            String toolName, @Nullable String userId) {
        if (result == null || !(result.get("content") instanceof List<?> content)) {
            return new Harvest(result, List.of());
        }
        boolean anyImage = false;
        for (Object block : content) {
            if (isImageBlock(block)) {
                anyImage = true;
                break;
            }
        }
        if (!anyImage) {
            return new Harvest(result, List.of());
        }

        List<AttachmentRef> refs = new ArrayList<>();
        List<Object> rewritten = new ArrayList<>(content.size());
        int harvested = 0;
        for (Object block : content) {
            if (!isImageBlock(block)) {
                rewritten.add(block);
                continue;
            }
            Map<String, Object> image = (Map<String, Object>) block;
            if (harvested >= MAX_IMAGES_PER_RESULT) {
                rewritten.add(dropped(image, "image limit of "
                        + MAX_IMAGES_PER_RESULT + " per tool result reached"));
                continue;
            }
            Map<String, Object> replacement =
                    store(image, tenantId, projectId, toolName, userId, refs);
            rewritten.add(replacement);
            harvested++;
        }
        Map<String, Object> out = new LinkedHashMap<>(result);
        out.put("content", rewritten);
        return new Harvest(out, List.copyOf(refs));
    }

    private Map<String, Object> store(
            Map<String, Object> image, String tenantId, String projectId,
            String toolName, @Nullable String userId, List<AttachmentRef> refs) {
        String mimeType = image.get("mimeType") instanceof String m && !m.isBlank()
                ? m.trim() : DEFAULT_MIME;
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(String.valueOf(image.get("data")));
        } catch (IllegalArgumentException e) {
            log.warn("ToolImageHarvester: tool '{}' returned an image block that is not "
                    + "valid base64 — leaving it in the result", toolName);
            return image;
        }
        if (bytes.length > maxBytesPerImage) {
            log.warn("ToolImageHarvester: image from tool '{}' is {} bytes, over the "
                    + "{} byte attachment limit — dropping it",
                    toolName, bytes.length, maxBytesPerImage);
            return dropped(image, "image of " + bytes.length + " bytes exceeds the "
                    + maxBytesPerImage + " byte attachment limit");
        }
        String path = FOLDER + "/" + toolName + "-" + System.nanoTime()
                + extensionFor(mimeType);
        try {
            DocumentDocument doc = documentService.createOrReplaceBinary(
                    tenantId, projectId, path, bytes, mimeType,
                    /*title*/ toolName + " output", /*tags*/ null, /*headers*/ null,
                    /*createdBy*/ userId,
                    contextFactory.writeActor(tenantId, userId, path));
            refs.add(new AttachmentRef(doc.getId()));
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("type", TYPE_IMAGE);
            ref.put("path", doc.getPath());
            ref.put("mimeType", mimeType);
            ref.put("sizeBytes", bytes.length);
            ref.put("note", "Stored as a document and attached to your next turn — "
                    + "you will see the image itself, no need to read this file.");
            return ref;
        } catch (RuntimeException e) {
            log.warn("ToolImageHarvester: cannot store image from tool '{}' at {}: {} — "
                    + "leaving the block in the result", toolName, path, e.toString());
            return image;
        }
    }

    /**
     * Replacement block for an image that will not be shown. Carries the
     * reason, because a model that asked for a screenshot and silently
     * gets nothing will simply ask again.
     */
    private static Map<String, Object> dropped(Map<String, Object> image, String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", TYPE_IMAGE);
        out.put("dropped", true);
        out.put("reason", reason);
        if (image.get("mimeType") instanceof String m) {
            out.put("mimeType", m);
        }
        return out;
    }

    private static boolean isImageBlock(@Nullable Object block) {
        return block instanceof Map<?, ?> m
                && TYPE_IMAGE.equals(m.get("type"))
                && m.get("data") instanceof String s
                && !s.isBlank();
    }

    private static String extensionFor(String mimeType) {
        return switch (mimeType.toLowerCase(Locale.ROOT)) {
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            default -> ".png";
        };
    }
}
