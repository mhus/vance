package de.mhus.vance.brain.tools.hotblack;

import de.mhus.vance.brain.hotblack.AnalyzeAudioRequest;
import de.mhus.vance.brain.hotblack.AnalyzeAudioResult;
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
 * The {@code audio_analyze} tool — audio understanding through
 * {@link HotblackService}. The audio comes from a document in the
 * caller's project (an audio file, or any attachment the user dropped
 * into the chat); the model answers {@code prompt} about the audio
 * itself — genre, instrumentation, tempo, mood, structure. Distinct
 * from {@code audio_transcribe}: transcribe maps speech to text,
 * analyze describes the sound.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AudioAnalyzeTool implements Tool {

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
                            "Document id of the audio file to analyze (the id shown in the "
                                    + "chat attachment hint or in the document browser). Required."),
                    "prompt",
                    Map.of(
                            "type",
                            "string",
                            "description",
                            "What to find out about the audio, e.g. 'Describe genre, "
                                    + "instruments, tempo, mood and song structure.' or 'Does this "
                                    + "clip match a calm piano background for a voiceover?' Required."),
                    "language",
                    Map.of(
                            "type",
                            "string",
                            "description",
                            "ISO-639-1 language code for the answer (e.g. 'de'). "
                                    + "Omit to answer in the chat language."),
                    "path",
                    Map.of(
                            "type",
                            "string",
                            "description",
                            "Optional document path — when set, the analysis is additionally "
                                    + "saved as a text document at this path.")),
            "required",
            List.of("documentId", "prompt"));

    @Override
    public String name() {
        return "audio_analyze";
    }

    @Override
    public String description() {
        return "Analyze an audio document (mp3, wav, m4a, ogg, …) — have it described instead of "
                + "transcribed: genre, instruments, tempo, mood, structure, recording quality. "
                + "Ask through 'prompt'; the answer comes back as text in the chat language. "
                + "Use audio_transcribe when you need the spoken words, audio_analyze when you "
                + "need to know what the audio IS. Synchronous.";
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
            throw new ToolException("audio_analyze requires a tenant scope");
        }

        AnalyzeAudioRequest request = AnalyzeAudioRequest.builder()
                .tenantId(ctx.tenantId())
                .projectId(ctx.projectId())
                .processId(ctx.processId())
                .userId(ctx.userId())
                .documentId(HotblackTools.readNonBlank(params, "documentId"))
                .prompt(HotblackTools.readNonBlank(params, "prompt"))
                .language(HotblackTools.readString(params, "language"))
                .path(HotblackTools.readString(params, "path"))
                .build();

        try {
            AnalyzeAudioResult result = hotblackService.analyze(request);
            return successResponse(result);
        } catch (HotblackException e) {
            log.info("audio_analyze failed: reason={} msg={}", e.getReason(), e.getMessage());
            return HotblackTools.errorResponse(e);
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage(), e);
        }
    }

    private static Map<String, Object> successResponse(AnalyzeAudioResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("analysis", r.getAnalysis());
        if (r.getLanguage() != null) {
            out.put("language", r.getLanguage());
        }
        out.put("modelUsed", r.getModelUsed());
        if (r.getCostUsd() != null) {
            out.put("costUsd", r.getCostUsd());
        }
        if (r.getAnalysisPath() != null) {
            out.put("analysisPath", r.getAnalysisPath());
        }
        out.put("durationMs", r.getDurationMs());
        return out;
    }
}
