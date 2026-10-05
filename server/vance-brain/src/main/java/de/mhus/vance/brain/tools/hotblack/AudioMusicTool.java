package de.mhus.vance.brain.tools.hotblack;

import de.mhus.vance.brain.hotblack.GenerateMusicRequest;
import de.mhus.vance.brain.hotblack.GenerateMusicResult;
import de.mhus.vance.brain.hotblack.HotblackException;
import de.mhus.vance.brain.hotblack.HotblackService;
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
 * The {@code audio_music} tool — music / non-speech audio generation
 * through {@link HotblackService}. One prompt → one finished clip as a
 * document. No editing, no stems, no loops — the clip is the unit.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AudioMusicTool implements Tool {

    private final HotblackService hotblackService;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type",
            "object",
            "properties",
            Map.of(
                    "prompt",
                    Map.of(
                            "type",
                            "string",
                            "description",
                            "What to compose: genre, mood, instrumentation, scene. "
                                    + "Example: 'gentle piano melody, calm, instrumental'. Required."),
                    "language",
                    Map.of(
                            "type",
                            "string",
                            "description",
                            "ISO-639-1 language of the lyrics for vocal tracks. Pass 'none' for "
                                    + "instrumental. Defaults to the conversation language."),
                    "durationSeconds",
                    Map.of(
                            "type",
                            "integer",
                            "description",
                            "Target clip length in seconds. The model treats it as a hint; "
                                    + "clips above the model cap are rejected."),
                    "path",
                    Map.of(
                            "type",
                            "string",
                            "description",
                            "Optional document path. If set, overwrites an existing file at that "
                                    + "path; otherwise the audio is written to `audio/<uuid>-<slug>.mp3`."),
                    "title",
                    Map.of(
                            "type",
                            "string",
                            "description",
                            "Optional human-readable title override. When absent, a short title "
                                    + "is generated from the prompt."),
                    "format",
                    Map.of(
                            "type", "string",
                            "enum", List.of("mp3", "wav"),
                            "description", "Output audio format. Defaults to mp3.")),
            "required",
            List.of("prompt"));

    @Override
    public String name() {
        return "audio_music";
    }

    @Override
    public String description() {
        return "Compose a music clip or sound-bed from a text prompt and save it as an audio "
                + "document. Synchronous — generation takes from tens of seconds to a few "
                + "minutes and costs real money per clip, so generate once and don't loop to "
                + "improve the result. For voice-over use `audio_speak`, for sound in existing "
                + "recordings use `audio_transcribe`.";
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
            throw new ToolException("audio_music requires a tenant scope");
        }

        GenerateMusicRequest request = GenerateMusicRequest.builder()
                .tenantId(ctx.tenantId())
                .projectId(ctx.projectId())
                .processId(ctx.processId())
                .userId(ctx.userId())
                .prompt(HotblackTools.readNonBlank(params, "prompt"))
                .language(HotblackTools.readString(params, "language"))
                .durationSeconds(readInt(params, "durationSeconds"))
                .path(HotblackTools.readString(params, "path"))
                .title(HotblackTools.readString(params, "title"))
                .format(HotblackTools.readString(params, "format"))
                .build();

        try {
            GenerateMusicResult result = hotblackService.compose(request);
            return successResponse(result);
        } catch (HotblackException e) {
            log.info("audio_music failed: reason={} msg={}", e.getReason(), e.getMessage());
            return HotblackTools.errorResponse(e);
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage(), e);
        }
    }

    private static Map<String, Object> successResponse(GenerateMusicResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", r.getPath());
        out.put("mimeType", r.getMimeType());
        out.put("sizeBytes", r.getSizeBytes());
        out.put("modelUsed", r.getModelUsed());
        out.put("durationMs", r.getDurationMs());
        if (r.getCostUsd() != null) {
            out.put("costUsd", r.getCostUsd());
        }
        if (r.getTitle() != null) {
            out.put("title", r.getTitle());
        }
        return out;
    }

    private static int readInt(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (raw == null) return 0;
        if (raw instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(raw.toString().trim());
        } catch (NumberFormatException e) {
            throw new ToolException("'" + key + "' must be a number", e);
        }
    }
}
