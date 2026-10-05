package de.mhus.vance.brain.tools.hotblack;

import de.mhus.vance.brain.hotblack.HotblackException;
import de.mhus.vance.brain.hotblack.HotblackService;
import de.mhus.vance.brain.hotblack.TranscribeAudioRequest;
import de.mhus.vance.brain.hotblack.TranscribeAudioResult;
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
 * The {@code audio_transcribe} tool — speech-to-text through
 * {@link HotblackService}. The audio comes from a document in the
 * caller's project (an audio file, or any attachment the user dropped
 * into the chat). The transcript is returned as tool result; pass
 * {@code path} to additionally store it as a text document.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AudioTranscribeTool implements Tool {

    private final HotblackService hotblackService;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type",
            "object",
            "properties",
            Map.of(
                    "documentId",
                    Map.of(
                            "type",
                            "string",
                            "description",
                            "Document id of the audio file to transcribe (the id shown in the "
                                    + "chat attachment hint or in the document browser). Required."),
                    "language",
                    Map.of(
                            "type",
                            "string",
                            "description",
                            "ISO-639-1 language code of the spoken audio (e.g. 'de'). Omit for "
                                    + "automatic language detection."),
                    "path",
                    Map.of(
                            "type",
                            "string",
                            "description",
                            "Optional document path — when set, the transcript is additionally "
                                    + "saved as a text document at this path.")),
            "required",
            List.of("documentId"));

    @Override
    public String name() {
        return "audio_transcribe";
    }

    @Override
    public String description() {
        return "Transcribe speech from an audio document (mp3, wav, m4a, ogg, webm, …) to text. "
                + "Returns the transcript. Synchronous — roughly real-time factor, so a "
                + "ten-minute recording can take a minute or two. Language is auto-detected "
                + "unless specified.";
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
            throw new ToolException("audio_transcribe requires a tenant scope");
        }

        TranscribeAudioRequest request = TranscribeAudioRequest.builder()
                .tenantId(ctx.tenantId())
                .projectId(ctx.projectId())
                .processId(ctx.processId())
                .userId(ctx.userId())
                .documentId(HotblackTools.readNonBlank(params, "documentId"))
                .language(HotblackTools.readString(params, "language"))
                .path(HotblackTools.readString(params, "path"))
                .build();

        try {
            TranscribeAudioResult result = hotblackService.transcribe(request);
            return successResponse(result);
        } catch (HotblackException e) {
            log.info("audio_transcribe failed: reason={} msg={}", e.getReason(), e.getMessage());
            return HotblackTools.errorResponse(e);
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage(), e);
        }
    }

    private static Map<String, Object> successResponse(TranscribeAudioResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", r.getText());
        if (r.getLanguage() != null) {
            out.put("language", r.getLanguage());
        }
        if (r.getDurationSeconds() != null) {
            out.put("durationSeconds", r.getDurationSeconds());
        }
        out.put("modelUsed", r.getModelUsed());
        if (r.getCostUsd() != null) {
            out.put("costUsd", r.getCostUsd());
        }
        if (r.getTranscriptPath() != null) {
            out.put("transcriptPath", r.getTranscriptPath());
        }
        out.put("durationMs", r.getDurationMs());
        return out;
    }
}
