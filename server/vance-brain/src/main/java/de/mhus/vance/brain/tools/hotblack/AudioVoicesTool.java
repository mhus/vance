package de.mhus.vance.brain.tools.hotblack;

import de.mhus.vance.brain.ai.AiModelResolver;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.audio.TtsModelInfo;
import de.mhus.vance.brain.hotblack.HotblackService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The {@code audio_voices` tool — read-only listing of the voices a
 * TTS model publishes, optionally filtered by language. Answers
 * "which voices exist — and one that fits my language?" before
 * {@code audio_speak} is called with a concrete {@code voice}.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AudioVoicesTool implements Tool {

    private final AiModelResolver modelResolver;
    private final ModelCatalog modelCatalog;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type",
            "object",
            "properties",
            Map.of(
                    "alias",
                    Map.of(
                            "type", "string",
                            "description", "Model alias to inspect. Defaults to 'default:tts'."),
                    "language",
                    Map.of(
                            "type",
                            "string",
                            "description",
                            "Optional ISO-639-1 filter — only voices whose locale fits this "
                                    + "language are returned (e.g. 'de' returns de-DE-* voices).")));

    @Override
    public String name() {
        return "audio_voices";
    }

    @Override
    public String description() {
        return "List the voices a text-to-speech model offers (id, locale, gender, description), "
                + "optionally filtered by language. Read-only. Use the returned id as the "
                + "`voice` parameter of `audio_speak`.";
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
        return Set.of("read-only");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx == null || ctx.tenantId() == null || ctx.tenantId().isBlank()) {
            throw new ToolException("audio_voices requires a tenant scope");
        }
        String alias = HotblackTools.readString(params, "alias");
        if (alias == null) {
            alias = HotblackService.DEFAULT_TTS_ALIAS;
        }
        String language = HotblackTools.readString(params, "language");

        AiModelResolver.Resolved resolved =
                modelResolver.resolveOrDefault(alias, ctx.tenantId(), ctx.projectId(), ctx.processId());
        TtsModelInfo modelInfo = modelCatalog
                .lookupTts(ctx.tenantId(), ctx.projectId(), resolved.providerInstance(), resolved.modelName())
                .or(() -> modelCatalog.lookupTts(
                        ctx.tenantId(), ctx.projectId(), resolved.provider(), resolved.modelName()))
                .orElseThrow(() -> new ToolException("No TTS model entry for " + resolved.provider() + ":"
                        + resolved.modelName() + " — add it to the model catalog with kind: tts"));

        List<Map<String, Object>> voices = new ArrayList<>();
        for (TtsModelInfo.Voice voice : modelInfo.supportedVoices()) {
            if (!voice.matchesLanguage(language)) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", voice.id());
            if (voice.locale() != null) {
                entry.put("locale", voice.locale());
            }
            if (voice.gender() != null) {
                entry.put("gender", voice.gender());
            }
            if (voice.description() != null) {
                entry.put("description", voice.description());
            }
            voices.add(entry);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", modelInfo.provider() + ":" + modelInfo.modelName());
        out.put("voices", voices);
        if (modelInfo.supportedVoices().isEmpty()) {
            out.put(
                    "hint",
                    "this model does not publish a voice list — omit `voice` in audio_speak "
                            + "to use the model default");
        }
        return out;
    }
}
