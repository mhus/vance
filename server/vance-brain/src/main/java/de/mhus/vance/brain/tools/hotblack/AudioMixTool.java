package de.mhus.vance.brain.tools.hotblack;

import de.mhus.vance.brain.audio.AudioManipulationException;
import de.mhus.vance.brain.audio.AudioManipulationService;
import de.mhus.vance.brain.audio.AudioOpResult;
import de.mhus.vance.brain.audio.MixRequest;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The {@code audio_mix} tool — mix two audio documents into one: base
 * (typically narration from {@code audio_speak}) plus overlay
 * (typically a music bed from {@code audio_music}) with gain, offset
 * and optional ducking. Local ffmpeg processing — no provider call, no
 * quota, no ledger.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AudioMixTool implements Tool {

    private final AudioManipulationService audioManipulationService;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type",
            "object",
            "properties",
            Map.of(
                    "path",
                    Map.of(
                            "type",
                            "string",
                            "description",
                            "Document path of the BASE audio (e.g. the narration). The mix length "
                                    + "follows the base. Use exactly one of `path` / `documentId`."),
                    "documentId",
                    Map.of(
                            "type",
                            "string",
                            "description",
                            "Document id of the BASE audio. Use exactly one of `path` / " + "`documentId`."),
                    "overlayPath",
                    Map.of(
                            "type",
                            "string",
                            "description",
                            "Document path of the OVERLAY audio (e.g. the music bed). Use exactly "
                                    + "one of `overlayPath` / `overlayDocumentId`."),
                    "overlayDocumentId",
                    Map.of(
                            "type",
                            "string",
                            "description",
                            "Document id of the OVERLAY audio. Use exactly one of `overlayPath` / "
                                    + "`overlayDocumentId`."),
                    "targetPath",
                    Map.of(
                            "type",
                            "string",
                            "description",
                            "Optional destination path. If set, the mix is written to a new "
                                    + "document; if omitted, the base is overwritten (the prior "
                                    + "version is archived by document versioning)."),
                    "baseGain",
                    Map.of(
                            "type",
                            "number",
                            "description",
                            "Linear gain for the base, 1.0 = unchanged, 0.5 = half amplitude. " + "Default 1.0."),
                    "overlayGain",
                    Map.of(
                            "type",
                            "number",
                            "description",
                            "Linear gain for the overlay, 1.0 = unchanged. Music beds usually "
                                    + "want 0.2-0.5 under narration. Default 1.0."),
                    "overlayAtSeconds",
                    Map.of(
                            "type",
                            "number",
                            "description",
                            "Position in the base timeline where the overlay starts, in seconds "
                                    + "(>= 0). Default 0."),
                    "duckOverlay",
                    Map.of(
                            "type",
                            "boolean",
                            "description",
                            "When true, the overlay is automatically lowered while the base "
                                    + "speaks (music ducks under narration). Default false."),
                    "fadeOutSeconds",
                    Map.of(
                            "type",
                            "number",
                            "description",
                            "Optional fade-out of the whole mix in seconds. Default 0.")),
            "required",
            List.of());

    @Override
    public String name() {
        return "audio_mix";
    }

    @Override
    public String description() {
        return "Mix two audio documents into one — e.g. narration from `audio_speak` (the base) "
                + "plus a music bed from `audio_music` (the overlay). The mix length follows the "
                + "base; the overlay starts at `overlayAtSeconds` and is cut off at the end of the "
                + "base. Set `duckOverlay=true` to lower the music while the narration speaks. "
                + "Local ffmpeg processing — instant, free, no AI involved.";
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
            throw new ToolException("audio_mix requires a tenant scope");
        }
        MixRequest request = MixRequest.builder()
                .tenantId(ctx.tenantId())
                .projectId(ctx.projectId())
                .processId(ctx.processId())
                .userId(ctx.userId())
                .path(HotblackTools.readString(params, "path"))
                .documentId(HotblackTools.readString(params, "documentId"))
                .overlayPath(HotblackTools.readString(params, "overlayPath"))
                .overlayDocumentId(HotblackTools.readString(params, "overlayDocumentId"))
                .targetPath(HotblackTools.readString(params, "targetPath"))
                .baseGain(HotblackTools.readDouble(params, "baseGain", 1.0))
                .overlayGain(HotblackTools.readDouble(params, "overlayGain", 1.0))
                .overlayAtSeconds(HotblackTools.readDouble(params, "overlayAtSeconds", 0.0))
                .duckOverlay(HotblackTools.readBoolean(params, "duckOverlay"))
                .fadeOutSeconds(HotblackTools.readDouble(params, "fadeOutSeconds", 0.0))
                .build();
        try {
            AudioOpResult result = audioManipulationService.mix(request);
            return HotblackTools.opResponse(result);
        } catch (AudioManipulationException e) {
            log.info("audio_mix failed: reason={} msg={}", e.getReason(), e.getMessage());
            return HotblackTools.errorResponse(e);
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage(), e);
        }
    }
}
