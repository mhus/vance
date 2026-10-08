package de.mhus.vance.brain.tools.hotblack;

import de.mhus.vance.brain.hotblack.GenerateSpeechRequest;
import de.mhus.vance.brain.hotblack.GenerateSpeechResult;
import de.mhus.vance.brain.hotblack.HotblackException;
import de.mhus.vance.brain.hotblack.HotblackService;
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
 * The {@code audio_speak} tool — text-to-speech through
 * {@link HotblackService}. Returns either a success object
 * ({@code path}, {@code mimeType}, {@code sizeBytes}, {@code modelUsed},
 * {@code language}, {@code voice}, {@code durationMs}, {@code costUsd},
 * {@code title}) or a structured failure ({@code error}, {@code message},
 * {@code retryable}).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AudioSpeakTool implements Tool {

    private final HotblackService hotblackService;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type",
            "object",
            "properties",
            Map.of(
                    "text",
                    Map.of(
                            "type",
                            "string",
                            "description",
                            "The text to speak. Markdown is stripped before synthesis, so feel "
                                    + "free to pass the answer text verbatim. Required, non-empty."),
                    "language",
                    Map.of(
                            "type",
                            "string",
                            "description",
                            "ISO-639-1 language code (e.g. 'de', 'en'). Defaults to the "
                                    + "conversation language, so omit it unless the text is in a "
                                    + "different language than the chat."),
                    "voice",
                    Map.of(
                            "type",
                            "string",
                            "description",
                            "Voice id — see the `audio_voices` tool for the voices the model "
                                    + "publishes. Defaults to the configured default voice."),
                    "path",
                    Map.of(
                            "type",
                            "string",
                            "description",
                            "Optional document path. If set, overwrites an existing file at "
                                    + "that path; otherwise the audio is written to "
                                    + "`audio/<uuid>-<slug>.mp3` with the slug derived from the text."),
                    "title",
                    Map.of(
                            "type",
                            "string",
                            "description",
                            "Optional human-readable title override. When absent, a short "
                                    + "title is generated from the text."),
                    "format",
                    Map.of(
                            "type",
                            "string",
                            "enum",
                            List.of("mp3", "wav"),
                            "description",
                            "Output audio format. Defaults to mp3 — or wav when the model serves "
                                    + "no mp3. An explicit value the model does not serve is rejected."),
                    "speed",
                    Map.of(
                            "type",
                            "number",
                            "description",
                            "Playback speed multiplier (1.0 = normal). Honoured only by "
                                    + "models with speed support.")),
            "required",
            List.of("text"));

    @Override
    public String name() {
        return "audio_speak";
    }

    @Override
    public String description() {
        return "Convert text to speech (TTS) and save the audio as a document. "
                + "Returns the document path so the user can play it. Synchronous — "
                + "takes a few seconds. The spoken language defaults to the conversation "
                + "language. Use `audio_voices` to list the voices a model offers.";
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
            throw new ToolException("audio_speak requires a tenant scope");
        }
        String text = readNonBlank(params, "text");

        GenerateSpeechRequest request = GenerateSpeechRequest.builder()
                .tenantId(ctx.tenantId())
                .projectId(ctx.projectId())
                .processId(ctx.processId())
                .userId(ctx.userId())
                .text(text)
                .language(readString(params, "language"))
                .voice(readString(params, "voice"))
                .path(readString(params, "path"))
                .title(readString(params, "title"))
                .format(readString(params, "format"))
                .speed(readDouble(params, "speed"))
                .build();

        try {
            GenerateSpeechResult result = hotblackService.speak(request);
            return successResponse(result);
        } catch (HotblackException e) {
            log.info("audio_speak failed: reason={} msg={}", e.getReason(), e.getMessage());
            return HotblackTools.errorResponse(e);
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage(), e);
        }
    }

    private static Map<String, Object> successResponse(GenerateSpeechResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", r.getPath());
        out.put("mimeType", r.getMimeType());
        out.put("sizeBytes", r.getSizeBytes());
        out.put("modelUsed", r.getModelUsed());
        out.put("language", r.getLanguage());
        if (r.getVoice() != null) {
            out.put("voice", r.getVoice());
        }
        out.put("durationMs", r.getDurationMs());
        if (r.getCostUsd() != null) {
            out.put("costUsd", r.getCostUsd());
        }
        if (r.getTitle() != null) {
            out.put("title", r.getTitle());
        }
        return out;
    }

    private static String readNonBlank(Map<String, Object> params, String key) {
        return HotblackTools.readNonBlank(params, key);
    }

    private static String readString(Map<String, Object> params, String key) {
        return HotblackTools.readString(params, key);
    }

    private static Double readDouble(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (raw == null) return null;
        if (raw instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(raw.toString().trim());
        } catch (NumberFormatException e) {
            throw new ToolException("'" + key + "' must be a number", e);
        }
    }
}
