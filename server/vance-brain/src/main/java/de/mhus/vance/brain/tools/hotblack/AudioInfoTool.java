package de.mhus.vance.brain.tools.hotblack;

import de.mhus.vance.brain.audio.AudioManipulationException;
import de.mhus.vance.brain.audio.AudioManipulationService;
import de.mhus.vance.brain.audio.AudioProbeResult;
import de.mhus.vance.brain.audio.ProbeRequest;
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
 * The {@code audio_info} tool — probe duration and format of an
 * existing audio document without changing anything. The numbers every
 * edit plan should be based on. Local ffmpeg probe — no provider call,
 * no quota, no ledger.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AudioInfoTool implements Tool {

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
                            "Document path of the audio file. Use exactly one of `path` / " + "`documentId`."),
                    "documentId",
                    Map.of(
                            "type",
                            "string",
                            "description",
                            "Document id of the audio file. Use exactly one of `path` / " + "`documentId`.")),
            "required",
            List.of());

    @Override
    public String name() {
        return "audio_info";
    }

    @Override
    public String description() {
        return "Probe an existing audio document: duration, container, codec, channels and "
                + "sample rate. Nothing is written or modified. Call this before `audio_trim`, "
                + "`audio_mix` or `audio_concat` to learn the duration of the source material — "
                + "every edit plan (cut windows, fade lengths, music-bed placement) should be "
                + "based on these numbers.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Set<String> labels() {
        return Set.of("read-only");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx == null || ctx.tenantId() == null || ctx.tenantId().isBlank()) {
            throw new ToolException("audio_info requires a tenant scope");
        }
        ProbeRequest request = ProbeRequest.builder()
                .tenantId(ctx.tenantId())
                .projectId(ctx.projectId())
                .processId(ctx.processId())
                .userId(ctx.userId())
                .path(HotblackTools.readString(params, "path"))
                .documentId(HotblackTools.readString(params, "documentId"))
                .build();
        try {
            AudioProbeResult result = audioManipulationService.probe(request);
            return toResponse(result);
        } catch (AudioManipulationException e) {
            log.info("audio_info failed: reason={} msg={}", e.getReason(), e.getMessage());
            return HotblackTools.errorResponse(e);
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage(), e);
        }
    }

    private static Map<String, Object> toResponse(AudioProbeResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", r.path());
        out.put("mimeType", r.mimeType());
        out.put("format", r.format());
        out.put("codec", r.codec());
        out.put("channels", r.channels());
        out.put("sampleRate", r.sampleRate());
        out.put("sizeBytes", r.sizeBytes());
        out.put("durationSeconds", r.durationSeconds());
        return out;
    }
}
