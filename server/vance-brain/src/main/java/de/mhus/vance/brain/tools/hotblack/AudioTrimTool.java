package de.mhus.vance.brain.tools.hotblack;

import de.mhus.vance.brain.audio.AudioManipulationException;
import de.mhus.vance.brain.audio.AudioManipulationService;
import de.mhus.vance.brain.audio.AudioOpResult;
import de.mhus.vance.brain.audio.TrimRequest;
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
 * The {@code audio_trim} tool — cut a window out of an audio document,
 * optionally faded. Local ffmpeg processing — no provider call, no
 * quota, no ledger.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AudioTrimTool implements Tool {

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
                            "Document path of the audio file to cut. Use exactly one of `path` / " + "`documentId`."),
                    "documentId",
                    Map.of(
                            "type",
                            "string",
                            "description",
                            "Document id of the audio file to cut. Use exactly one of `path` / " + "`documentId`."),
                    "targetPath",
                    Map.of(
                            "type",
                            "string",
                            "description",
                            "Optional destination path. If set, the cut is written to a new "
                                    + "document and the source stays untouched; if omitted, the "
                                    + "source is overwritten (the prior version is archived by "
                                    + "document versioning)."),
                    "startSeconds",
                    Map.of(
                            "type",
                            "number",
                            "description",
                            "Cut start in seconds (>= 0). Required. Learn the duration from " + "`audio_info` first."),
                    "endSeconds",
                    Map.of(
                            "type",
                            "number",
                            "description",
                            "Cut end in seconds, exclusive. Defaults to the end of the file."),
                    "fadeInSeconds",
                    Map.of(
                            "type",
                            "number",
                            "description",
                            "Optional fade-in of the cut in seconds (>= 0). Default 0."),
                    "fadeOutSeconds",
                    Map.of(
                            "type",
                            "number",
                            "description",
                            "Optional fade-out of the cut in seconds (>= 0), must be shorter than "
                                    + "the cut. Default 0.")),
            "required",
            List.of("startSeconds"));

    @Override
    public String name() {
        return "audio_trim";
    }

    @Override
    public String description() {
        return "Cut a window out of an audio document: keeps [startSeconds, endSeconds), removes "
                + "everything else, optionally fades the cut in and out. Use `audio_info` first to "
                + "learn the duration. The result keeps the source format (mp3 or wav); other "
                + "sources are re-encoded to mp3. Local ffmpeg processing — instant, free, no AI "
                + "involved. For assembling clips use `audio_concat`, for volume/format changes "
                + "`audio_convert`.";
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
            throw new ToolException("audio_trim requires a tenant scope");
        }
        TrimRequest request = TrimRequest.builder()
                .tenantId(ctx.tenantId())
                .projectId(ctx.projectId())
                .processId(ctx.processId())
                .userId(ctx.userId())
                .path(HotblackTools.readString(params, "path"))
                .documentId(HotblackTools.readString(params, "documentId"))
                .targetPath(HotblackTools.readString(params, "targetPath"))
                .startSeconds(HotblackTools.readDouble(params, "startSeconds", 0.0))
                .endSeconds(HotblackTools.readDouble(params, "endSeconds"))
                .fadeInSeconds(HotblackTools.readDouble(params, "fadeInSeconds", 0.0))
                .fadeOutSeconds(HotblackTools.readDouble(params, "fadeOutSeconds", 0.0))
                .build();
        try {
            AudioOpResult result = audioManipulationService.trim(request);
            return HotblackTools.opResponse(result);
        } catch (AudioManipulationException e) {
            log.info("audio_trim failed: reason={} msg={}", e.getReason(), e.getMessage());
            return HotblackTools.errorResponse(e);
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage(), e);
        }
    }
}
