package de.mhus.vance.brain.tools.hotblack;

import de.mhus.vance.brain.audio.AudioManipulationException;
import de.mhus.vance.brain.audio.AudioManipulationService;
import de.mhus.vance.brain.audio.AudioOpResult;
import de.mhus.vance.brain.audio.ConvertRequest;
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
 * The {@code audio_convert} tool — re-encode an audio document into
 * mp3 or wav, optionally resampled, downmixed, bitrate-set and
 * loudness-normalised. The normaliser for browser uploads (webm/ogg →
 * mp3/wav). Local ffmpeg processing — no provider call, no quota, no
 * ledger.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AudioConvertTool implements Tool {

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
                            "Document id of the audio file. Use exactly one of `path` / " + "`documentId`."),
                    "targetPath",
                    Map.of(
                            "type",
                            "string",
                            "description",
                            "Optional destination path. If set, the result is written to a new "
                                    + "document; if omitted, the source is overwritten (the prior "
                                    + "version is archived by document versioning)."),
                    "format",
                    Map.of(
                            "type",
                            "string",
                            "enum",
                            List.of("mp3", "wav"),
                            "description",
                            "Target format. Required. mp3 for playback/podcasts, wav for editing "
                                    + "or when sample accuracy matters."),
                    "sampleRate",
                    Map.of(
                            "type",
                            "integer",
                            "description",
                            "Optional target sample rate in Hz (e.g. 16000 for speech, 44100 for "
                                    + "music). Default: keep the source rate."),
                    "channels",
                    Map.of(
                            "type",
                            "integer",
                            "enum",
                            List.of(1, 2),
                            "description",
                            "Optional channel count: 1 = mono, 2 = stereo. Default: keep the " + "source channels."),
                    "bitrateKbps",
                    Map.of(
                            "type",
                            "integer",
                            "description",
                            "Optional mp3 bitrate in kbit/s (32-320). Default 192. Applies to mp3 " + "only."),
                    "normalize",
                    Map.of(
                            "type",
                            "boolean",
                            "description",
                            "When true, loudness is normalised (EBU R128) during the re-encode. "
                                    + "Use for uneven narration or loud music. Default false.")),
            "required",
            List.of("format"));

    @Override
    public String name() {
        return "audio_convert";
    }

    @Override
    public String description() {
        return "Re-encode an audio document into mp3 or wav — the normaliser for browser uploads "
                + "(webm/ogg) and uneven material. Optionally resample (sampleRate), downmix "
                + "(channels), set the mp3 bitrate and normalise loudness (normalize). Local "
                + "ffmpeg processing — instant, free, no AI involved. For cutting use "
                + "`audio_trim`, for assembling `audio_concat`.";
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
        return Set.of(ToolLabels.WORKER, "write");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx == null || ctx.tenantId() == null || ctx.tenantId().isBlank()) {
            throw new ToolException("audio_convert requires a tenant scope");
        }
        ConvertRequest request = ConvertRequest.builder()
                .tenantId(ctx.tenantId())
                .projectId(ctx.projectId())
                .processId(ctx.processId())
                .userId(ctx.userId())
                .path(HotblackTools.readString(params, "path"))
                .documentId(HotblackTools.readString(params, "documentId"))
                .targetPath(HotblackTools.readString(params, "targetPath"))
                .format(HotblackTools.readNonBlank(params, "format"))
                .sampleRate(HotblackTools.readInt(params, "sampleRate"))
                .channels(HotblackTools.readInt(params, "channels"))
                .bitrateKbps(HotblackTools.readInt(params, "bitrateKbps"))
                .normalize(HotblackTools.readBoolean(params, "normalize"))
                .build();
        try {
            AudioOpResult result = audioManipulationService.convert(request);
            return HotblackTools.opResponse(result);
        } catch (AudioManipulationException e) {
            log.info("audio_convert failed: reason={} msg={}", e.getReason(), e.getMessage());
            return HotblackTools.errorResponse(e);
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage(), e);
        }
    }
}
