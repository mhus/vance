package de.mhus.vance.brain.tools.hotblack;

import de.mhus.vance.brain.audio.AudioManipulationException;
import de.mhus.vance.brain.audio.AudioManipulationService;
import de.mhus.vance.brain.audio.AudioOpResult;
import de.mhus.vance.brain.audio.ConcatRequest;
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
 * The {@code audio_concat} tool — join audio clips in order into one
 * document, hard cut or crossfaded. Local ffmpeg processing — no
 * provider call, no quota, no ledger.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AudioConcatTool implements Tool {

    private final AudioManipulationService audioManipulationService;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type",
            "object",
            "properties",
            Map.of(
                    "paths",
                    Map.of(
                            "type",
                            "array",
                            "items",
                            Map.of("type", "string"),
                            "description",
                            "Document paths of the clips in join order, at least 2. Use exactly "
                                    + "one of `paths` / `documentIds`."),
                    "documentIds",
                    Map.of(
                            "type",
                            "array",
                            "items",
                            Map.of("type", "string"),
                            "description",
                            "Document ids of the clips in join order, at least 2. Use exactly one "
                                    + "of `paths` / `documentIds`."),
                    "targetPath",
                    Map.of(
                            "type",
                            "string",
                            "description",
                            "Destination path of the joined document. Required — concat never "
                                    + "overwrites its clips. Pick something like `audio/out.mp3`."),
                    "crossfadeSeconds",
                    Map.of(
                            "type",
                            "number",
                            "description",
                            "Optional crossfade between neighbouring clips in seconds (>= 0). "
                                    + "Default 0 = hard cut.")),
            "required",
            List.of("targetPath"));

    @Override
    public String name() {
        return "audio_concat";
    }

    @Override
    public String description() {
        return "Join audio clips in order into one document — e.g. intro + narration + outro. "
                + "Clips are normalised to a common format before joining, so mixing mp3 and wav "
                + "is fine. Set `crossfadeSeconds` to blend neighbouring clips instead of cutting "
                + "hard. The result keeps the first clip's format (mp3 or wav); other sources are "
                + "re-encoded to mp3. Local ffmpeg processing — instant, free, no AI involved. To "
                + "overlay sounds on top of each other use `audio_mix` instead.";
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
        return Set.of("write");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx == null || ctx.tenantId() == null || ctx.tenantId().isBlank()) {
            throw new ToolException("audio_concat requires a tenant scope");
        }
        ConcatRequest request = ConcatRequest.builder()
                .tenantId(ctx.tenantId())
                .projectId(ctx.projectId())
                .processId(ctx.processId())
                .userId(ctx.userId())
                .paths(HotblackTools.readList(params, "paths"))
                .documentIds(HotblackTools.readList(params, "documentIds"))
                .targetPath(HotblackTools.readString(params, "targetPath"))
                .crossfadeSeconds(HotblackTools.readDouble(params, "crossfadeSeconds", 0.0))
                .build();
        try {
            AudioOpResult result = audioManipulationService.concat(request);
            return HotblackTools.opResponse(result);
        } catch (AudioManipulationException e) {
            log.info("audio_concat failed: reason={} msg={}", e.getReason(), e.getMessage());
            return HotblackTools.errorResponse(e);
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage(), e);
        }
    }
}
