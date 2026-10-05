package de.mhus.vance.brain.hotblack;

import de.mhus.vance.api.attachment.AttachmentRef;
import de.mhus.vance.api.progress.StatusTag;
import de.mhus.vance.brain.ai.AiModelResolver;
import de.mhus.vance.brain.ai.ChatBehaviorBuilder;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.UsageMeasurement;
import de.mhus.vance.brain.ai.attachment.AttachmentException;
import de.mhus.vance.brain.ai.attachment.AttachmentResolver;
import de.mhus.vance.brain.ai.attachment.ResolvedAttachment;
import de.mhus.vance.brain.ai.audio.AiAudioConfig;
import de.mhus.vance.brain.ai.audio.AiAudioException;
import de.mhus.vance.brain.ai.audio.AiAudioService;
import de.mhus.vance.brain.ai.audio.AudioSource;
import de.mhus.vance.brain.ai.audio.DocumentAudioDestinationStream;
import de.mhus.vance.brain.ai.audio.MusicModelInfo;
import de.mhus.vance.brain.ai.audio.SttModelInfo;
import de.mhus.vance.brain.ai.audio.SttResult;
import de.mhus.vance.brain.ai.audio.TtsModelInfo;
import de.mhus.vance.brain.ai.audio.TtsRequest;
import de.mhus.vance.brain.ai.light.LightLlmException;
import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.brain.progress.ProgressEmitter;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.hotblack.AudioCallRecord;
import de.mhus.vance.shared.llmusage.CallAttribution;
import de.mhus.vance.shared.llmusage.LlmUsageService;
import de.mhus.vance.shared.llmusage.UsageOutcome;
import de.mhus.vance.shared.settings.LanguageResolver;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.voice.MarkdownToSpeech;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Entry point of the Hotblack audio stack — the synchronous,
 * single-shot service behind {@code audio_speak}, {@code
 * audio_transcribe} and {@code audio_music}: alias resolution, quota,
 * title generation, provider dispatch through {@link AiAudioService},
 * and the commit of the generated audio to the document store.
 *
 * <p>Same per-call sequence as {@code FenchurchService} (validation →
 * quota reserve → heartbeat → provider call → call-record + usage
 * booking) and the same synchronous contract: the caller's lane lock
 * is the only serialisation.
 *
 * <p>Language is first-class: synthesis defaults to the
 * {@code chat.language} cascade (the assistant speaks the language it
 * answers in), transcription defaults to provider auto-detect (the
 * user speaks what they speak), music carries the lyric language.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HotblackService {

    public static final String DEFAULT_TTS_ALIAS = "default:tts";
    public static final String DEFAULT_STT_ALIAS = "default:stt";
    public static final String DEFAULT_MUSIC_ALIAS = "default:music";

    public static final String SETTING_ENABLED = "ai.hotblack.enabled";
    public static final String SETTING_TTS_ENABLED = "ai.hotblack.tts.enabled";
    public static final String SETTING_STT_ENABLED = "ai.hotblack.stt.enabled";
    public static final String SETTING_MUSIC_ENABLED = "ai.hotblack.music.enabled";
    public static final String SETTING_TIMEOUT = "ai.hotblack.timeout";
    public static final String SETTING_HEARTBEAT = "ai.hotblack.heartbeat-interval-sec";
    public static final String SETTING_DEFAULT_VOICE = "ai.hotblack.default-voice";
    public static final String SETTING_DEFAULT_FORMAT = "ai.hotblack.default-format";
    public static final String SETTING_AUTO_TITLE = "ai.hotblack.auto-title-from-prompt";

    public static final String AUDIO_TITLE_RECIPE = "audio-title";
    public static final String MODALITY_TTS = "tts";
    public static final String MODALITY_STT = "stt";
    public static final String MODALITY_MUSIC = "music";

    public static final int DEFAULT_HEARTBEAT_INTERVAL_SECONDS = 30;

    private final AudioCallTracker callTracker;
    private final AiModelResolver modelResolver;
    private final ModelCatalog modelCatalog;
    private final AiAudioService audioService;
    private final de.mhus.vance.brain.tools.ToolInterruptChecker interruptChecker;
    private final LightLlmService lightLlm;
    private final SettingService settingService;
    private final LanguageResolver languageResolver;
    private final DocumentService documentService;
    private final de.mhus.vance.brain.permission.SecurityContextFactory contextFactory;
    private final ProgressEmitter progressEmitter;
    private final ThinkProcessService thinkProcessService;
    private final de.mhus.vance.brain.ai.UsageSink usageSink;
    private final AttachmentResolver attachmentResolver;

    @Value("${vance.hotblack.scheduler-pool-size:2}")
    private int schedulerPoolSize;

    private ScheduledExecutorService scheduler;

    @jakarta.annotation.PostConstruct
    void init() {
        int size = Math.max(1, schedulerPoolSize);
        AtomicInteger seq = new AtomicInteger();
        this.scheduler = Executors.newScheduledThreadPool(size, r -> {
            Thread t = new Thread(r, "hotblack-heartbeat-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    // ──────────────────── TTS ────────────────────

    /**
     * Synthesize speech from {@code request.getText()} and commit the
     * audio document. Synchronous; failures throw
     * {@link HotblackException}.
     */
    public GenerateSpeechResult speak(GenerateSpeechRequest request) {
        validateScope(request.getTenantId(), request.getText(), "text");
        ensureEnabled(
                MODALITY_TTS,
                SETTING_TTS_ENABLED,
                request.getTenantId(),
                request.getProjectId(),
                request.getProcessId());

        String text = MarkdownToSpeech.strip(request.getText()).trim();
        if (text.isBlank()) {
            throw new HotblackException(
                    HotblackException.Reason.INVALID_CHOICE, "Nothing left to speak after markdown stripping");
        }

        ResolvedModel resolved = resolveModel(
                request.getAlias() == null || request.getAlias().isBlank() ? DEFAULT_TTS_ALIAS : request.getAlias(),
                MODALITY_TTS,
                request.getTenantId(),
                request.getProjectId(),
                request.getProcessId());
        TtsModelInfo modelInfo = modelCatalog
                .lookupTts(
                        request.getTenantId(),
                        request.getProjectId(),
                        resolved.resolved().providerInstance(),
                        resolved.resolved().modelName())
                .or(() -> modelCatalog.lookupTts(
                        request.getTenantId(),
                        request.getProjectId(),
                        resolved.resolved().provider(),
                        resolved.resolved().modelName()))
                .orElseThrow(() -> new HotblackException(
                        HotblackException.Reason.INVALID_CHOICE,
                        "No TTS model entry for " + resolved.resolved().provider() + ":"
                                + resolved.resolved().modelName()
                                + " — add it to the model catalog with kind: tts"));

        String language = request.getLanguage() != null
                        && !request.getLanguage().isBlank()
                ? request.getLanguage().trim()
                : languageResolver.chatLanguage(
                        request.getTenantId(), request.getUserId(), request.getProjectId(), request.getProcessId());
        if (!modelInfo.supportsLanguage(language)) {
            throw new HotblackException(
                    HotblackException.Reason.UNSUPPORTED_LANGUAGE,
                    "Model " + modelInfo.provider() + ":" + modelInfo.modelName()
                            + " does not support language '" + language + "' — supported: "
                            + modelInfo.supportedLanguages());
        }

        String voice = request.getVoice() != null && !request.getVoice().isBlank()
                ? request.getVoice().trim()
                : settingService.getStringValueCascade(
                        request.getTenantId(), request.getProjectId(), request.getProcessId(), SETTING_DEFAULT_VOICE);
        voice = validateVoice(voice, language, modelInfo);

        String format = resolveFormat(
                request.getFormat(),
                modelInfo.supportedFormats(),
                modelInfo.provider() + ":" + modelInfo.modelName(),
                request.getTenantId(),
                request.getProjectId(),
                request.getProcessId());

        if (text.length() > modelInfo.maxInputChars()) {
            throw new HotblackException(
                    HotblackException.Reason.TEXT_TOO_LONG,
                    "Text is " + text.length() + " chars; model " + modelInfo.provider() + ":" + modelInfo.modelName()
                            + " caps at " + modelInfo.maxInputChars());
        }

        long callStart = System.currentTimeMillis();
        TitleResolution title = resolveTitleAndSlug(
                request.getTenantId(),
                request.getUserId(),
                request.getProjectId(),
                request.getProcessId(),
                request.getTitle(),
                text);
        String path = resolvePath(request.getPath(), title, format);

        AiAudioConfig config = buildConfig(
                resolved,
                modelInfo.timeoutSeconds(),
                request.getTenantId(),
                request.getProjectId(),
                request.getProcessId());
        ThinkProcessDocument process = loadProcess(request.getProcessId());
        interruptChecker.throwIfHalted(process == null ? null : process.getId());

        String reserveId = reserve(MODALITY_TTS, request);
        ScheduledFuture<?> heartbeat = startHeartbeat(process, "speech (" + resolved.alias() + ")", callStart, request);

        Double estimate = estimateTtsCost(modelInfo, text);
        DocumentDocument committed;
        Double cost;
        try {
            DocumentAudioDestinationStream stream = openStream(request, path, title.title());
            audioService.synthesize(config, new TtsRequest(text, language, voice, format, request.getSpeed()), stream);
            // The committed document is the proof of success — resolved inside
            // the try so a missing commit finalizes the reserve row as a
            // failure instead of leaving its pending row behind.
            committed = requireCommitted(request.getTenantId(), resolveProjectId(request), path);
            cost = effectiveCostUsd(estimate, committed);
        } catch (AiAudioException e) {
            cancelHeartbeat(heartbeat);
            recordFailure(
                    reserveId,
                    request.getTenantId(),
                    request.getUserId(),
                    request.getProjectId(),
                    request.getProcessId(),
                    resolved.alias(),
                    config,
                    MODALITY_TTS,
                    callStart,
                    text.length(),
                    e);
            throw mapProviderError(e, config);
        } catch (RuntimeException e) {
            cancelHeartbeat(heartbeat);
            recordFailure(
                    reserveId,
                    request.getTenantId(),
                    request.getUserId(),
                    request.getProjectId(),
                    request.getProcessId(),
                    resolved.alias(),
                    config,
                    MODALITY_TTS,
                    callStart,
                    text.length(),
                    e);
            if (e instanceof HotblackException) {
                // The commit proof failing is already a HotblackException —
                // keep its reason and message instead of wrapping it again.
                throw e;
            }
            throw new HotblackException(
                    HotblackException.Reason.PROVIDER_ERROR,
                    "Speech generation failed for " + config.fullName() + ": " + e.getMessage(),
                    e);
        }
        cancelHeartbeat(heartbeat);

        long durationMs = System.currentTimeMillis() - callStart;
        recordSuccess(
                reserveId,
                request.getTenantId(),
                request.getUserId(),
                request.getProjectId(),
                request.getProcessId(),
                resolved.alias(),
                config,
                MODALITY_TTS,
                callStart,
                durationMs,
                text.length(),
                cost);
        bookUsage(
                request.getTenantId(),
                request.getProjectId(),
                request.getProcessId(),
                config,
                cost,
                UsageOutcome.SUCCESS,
                durationMs);

        return GenerateSpeechResult.builder()
                .path(committed.getPath())
                .mimeType(committed.getMimeType() == null ? "audio/mpeg" : committed.getMimeType())
                .sizeBytes(committed.getSize())
                .modelUsed(config.fullName())
                .language(language)
                .voice(voice)
                .durationMs(durationMs)
                .costUsd(cost)
                .title(committed.getTitle())
                .build();
    }

    // ──────────────────── STT ────────────────────

    /**
     * Transcribe an audio document (or raw audio bytes from internal
     * callers) and optionally commit the transcript as a text document.
     * Language defaults to provider auto-detect.
     */
    public TranscribeAudioResult transcribe(TranscribeAudioRequest request) {
        validateScope(request.getTenantId(), null, null);
        ensureEnabled(
                MODALITY_STT,
                SETTING_STT_ENABLED,
                request.getTenantId(),
                request.getProjectId(),
                request.getProcessId());

        AudioSource source = resolveAudioSource(request);

        ResolvedModel resolved = resolveModel(
                request.getAlias() == null || request.getAlias().isBlank() ? DEFAULT_STT_ALIAS : request.getAlias(),
                MODALITY_STT,
                request.getTenantId(),
                request.getProjectId(),
                request.getProcessId());
        SttModelInfo modelInfo = modelCatalog
                .lookupStt(
                        request.getTenantId(),
                        request.getProjectId(),
                        resolved.resolved().providerInstance(),
                        resolved.resolved().modelName())
                .or(() -> modelCatalog.lookupStt(
                        request.getTenantId(),
                        request.getProjectId(),
                        resolved.resolved().provider(),
                        resolved.resolved().modelName()))
                .orElseThrow(() -> new HotblackException(
                        HotblackException.Reason.INVALID_CHOICE,
                        "No stt model entry for " + resolved.resolved().provider() + ":"
                                + resolved.resolved().modelName()
                                + " — add it to the model catalog with kind: stt"));

        String language =
                request.getLanguage() != null && !request.getLanguage().isBlank()
                        ? request.getLanguage().trim()
                        : null;
        if (!modelInfo.supportsLanguage(language)) {
            throw new HotblackException(
                    HotblackException.Reason.UNSUPPORTED_LANGUAGE,
                    "Model " + modelInfo.provider() + ":" + modelInfo.modelName() + " does not support language '"
                            + language + "'");
        }
        if (!acceptsAudioFormat(modelInfo.supportedFormats(), source.format())) {
            throw new HotblackException(
                    HotblackException.Reason.UNSUPPORTED_FORMAT,
                    "Model " + modelInfo.provider() + ":" + modelInfo.modelName()
                            + " does not accept audio format '" + source.format() + "' — it accepts "
                            + modelInfo.supportedFormats());
        }

        long callStart = System.currentTimeMillis();
        AiAudioConfig config = buildConfig(
                resolved,
                modelInfo.timeoutSeconds(),
                request.getTenantId(),
                request.getProjectId(),
                request.getProcessId());
        ThinkProcessDocument process = loadProcess(request.getProcessId());
        interruptChecker.throwIfHalted(process == null ? null : process.getId());

        String reserveId = reserve(
                MODALITY_STT,
                request.getTenantId(),
                request.getUserId(),
                request.getProjectId(),
                request.getProcessId(),
                request.getAlias());
        ScheduledFuture<?> heartbeat = startHeartbeat(
                process,
                "transcript (" + resolved.alias() + ")",
                callStart,
                request.getTenantId(),
                request.getProjectId(),
                request.getProcessId());

        SttResult result;
        try {
            result = audioService.transcribe(config, source, language);
        } catch (AiAudioException e) {
            cancelHeartbeat(heartbeat);
            recordFailure(
                    reserveId,
                    request.getTenantId(),
                    request.getUserId(),
                    request.getProjectId(),
                    request.getProcessId(),
                    resolved.alias(),
                    config,
                    MODALITY_STT,
                    callStart,
                    0,
                    e);
            throw mapProviderError(e, config);
        } catch (RuntimeException e) {
            cancelHeartbeat(heartbeat);
            recordFailure(
                    reserveId,
                    request.getTenantId(),
                    request.getUserId(),
                    request.getProjectId(),
                    request.getProcessId(),
                    resolved.alias(),
                    config,
                    MODALITY_STT,
                    callStart,
                    0,
                    e);
            throw new HotblackException(
                    HotblackException.Reason.PROVIDER_ERROR,
                    "Transcription failed for " + config.fullName() + ": " + e.getMessage(),
                    e);
        }
        cancelHeartbeat(heartbeat);

        long durationMs = System.currentTimeMillis() - callStart;
        long inputUnits = result.durationSeconds() == null ? 0 : Math.round(result.durationSeconds());
        Double cost = result.reportedCostUsd() != null
                ? result.reportedCostUsd()
                : modelInfo.costForSeconds(result.durationSeconds() == null ? 0 : result.durationSeconds());
        recordSuccess(
                reserveId,
                request.getTenantId(),
                request.getUserId(),
                request.getProjectId(),
                request.getProcessId(),
                resolved.alias(),
                config,
                MODALITY_STT,
                callStart,
                durationMs,
                inputUnits,
                cost);
        bookUsage(
                request.getTenantId(),
                request.getProjectId(),
                request.getProcessId(),
                config,
                cost,
                UsageOutcome.SUCCESS,
                durationMs);

        String transcriptPath = null;
        if (request.getPath() != null && !request.getPath().isBlank()) {
            transcriptPath = request.getPath().trim();
            documentService.createOrReplaceBinary(
                    request.getTenantId(),
                    resolveProjectId(request),
                    transcriptPath,
                    result.text().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    "text/markdown",
                    "Transcript — " + (source.name() == null ? config.fullName() : source.name()),
                    List.of("text", "ai-generated", "hotblack"),
                    Map.of("model", config.fullName()),
                    request.getUserId(),
                    contextFactory.writeActor(request.getTenantId(), request.getUserId(), transcriptPath));
        }

        return TranscribeAudioResult.builder()
                .text(result.text())
                .language(result.language() == null ? language : result.language())
                .durationSeconds(result.durationSeconds())
                .modelUsed(config.fullName())
                .costUsd(cost)
                .transcriptPath(transcriptPath)
                .durationMs(durationMs)
                .build();
    }

    // ──────────────────── Music ────────────────────

    /**
     * Generate music / non-speech audio from a prompt and commit the
     * audio document. The target duration and lyric language travel in
     * the composed prompt (plus as native parameters for providers
     * whose wire has them).
     */
    public GenerateMusicResult compose(GenerateMusicRequest request) {
        validateScope(request.getTenantId(), request.getPrompt(), "prompt");
        ensureEnabled(
                MODALITY_MUSIC,
                SETTING_MUSIC_ENABLED,
                request.getTenantId(),
                request.getProjectId(),
                request.getProcessId());

        ResolvedModel resolved = resolveModel(
                request.getAlias() == null || request.getAlias().isBlank() ? DEFAULT_MUSIC_ALIAS : request.getAlias(),
                MODALITY_MUSIC,
                request.getTenantId(),
                request.getProjectId(),
                request.getProcessId());
        MusicModelInfo modelInfo = modelCatalog
                .lookupMusic(
                        request.getTenantId(),
                        request.getProjectId(),
                        resolved.resolved().providerInstance(),
                        resolved.resolved().modelName())
                .or(() -> modelCatalog.lookupMusic(
                        request.getTenantId(),
                        request.getProjectId(),
                        resolved.resolved().provider(),
                        resolved.resolved().modelName()))
                .orElseThrow(() -> new HotblackException(
                        HotblackException.Reason.INVALID_CHOICE,
                        "No music model entry for " + resolved.resolved().provider() + ":"
                                + resolved.resolved().modelName()
                                + " — add it to the model catalog with kind: music"));

        String language = "none".equalsIgnoreCase(request.getLanguage())
                ? null
                : request.getLanguage() != null && !request.getLanguage().isBlank()
                        ? request.getLanguage().trim()
                        : languageResolver.chatLanguage(
                                request.getTenantId(),
                                request.getUserId(),
                                request.getProjectId(),
                                request.getProcessId());
        if (!modelInfo.supportsLanguage(language)) {
            throw new HotblackException(
                    HotblackException.Reason.UNSUPPORTED_LANGUAGE,
                    "Model " + modelInfo.provider() + ":" + modelInfo.modelName() + " does not support lyric language '"
                            + language + "'");
        }

        int durationSeconds = request.getDurationSeconds();
        if (durationSeconds > modelInfo.maxDurationSeconds()) {
            throw new HotblackException(
                    HotblackException.Reason.INVALID_CHOICE,
                    "Model " + modelInfo.provider() + ":" + modelInfo.modelName()
                            + " caps clips at " + modelInfo.maxDurationSeconds() + " seconds, requested "
                            + durationSeconds);
        }

        String prompt = composeMusicPrompt(request.getPrompt(), language, durationSeconds);
        String format = resolveFormat(
                request.getFormat(),
                modelInfo.supportedFormats(),
                modelInfo.provider() + ":" + modelInfo.modelName(),
                request.getTenantId(),
                request.getProjectId(),
                request.getProcessId());

        long callStart = System.currentTimeMillis();
        TitleResolution title = resolveTitleAndSlug(
                request.getTenantId(),
                request.getUserId(),
                request.getProjectId(),
                request.getProcessId(),
                request.getTitle(),
                prompt);
        String path = resolvePath(request.getPath(), title, format);

        AiAudioConfig config = buildConfig(
                resolved,
                modelInfo.timeoutSeconds(),
                request.getTenantId(),
                request.getProjectId(),
                request.getProcessId());
        ThinkProcessDocument process = loadProcess(request.getProcessId());
        interruptChecker.throwIfHalted(process == null ? null : process.getId());

        String reserveId = reserve(MODALITY_MUSIC, request);
        ScheduledFuture<?> heartbeat = startHeartbeat(process, "music (" + resolved.alias() + ")", callStart, request);

        Double estimate = estimateMusicCost(modelInfo, durationSeconds);
        DocumentDocument committed;
        Double cost;
        try {
            DocumentAudioDestinationStream stream = openStream(request, path, title.title());
            audioService.generateMusic(config, prompt, durationSeconds, stream);
            // The committed document is the proof of success — resolved inside
            // the try so a missing commit finalizes the reserve row as a
            // failure instead of leaving its pending row behind.
            committed = requireCommitted(request.getTenantId(), resolveProjectId(request), path);
            cost = effectiveCostUsd(estimate, committed);
        } catch (AiAudioException e) {
            cancelHeartbeat(heartbeat);
            recordFailure(
                    reserveId,
                    request.getTenantId(),
                    request.getUserId(),
                    request.getProjectId(),
                    request.getProcessId(),
                    resolved.alias(),
                    config,
                    MODALITY_MUSIC,
                    callStart,
                    0,
                    e);
            throw mapProviderError(e, config);
        } catch (RuntimeException e) {
            cancelHeartbeat(heartbeat);
            recordFailure(
                    reserveId,
                    request.getTenantId(),
                    request.getUserId(),
                    request.getProjectId(),
                    request.getProcessId(),
                    resolved.alias(),
                    config,
                    MODALITY_MUSIC,
                    callStart,
                    0,
                    e);
            if (e instanceof HotblackException) {
                // The commit proof failing is already a HotblackException —
                // keep its reason and message instead of wrapping it again.
                throw e;
            }
            throw new HotblackException(
                    HotblackException.Reason.PROVIDER_ERROR,
                    "Music generation failed for " + config.fullName() + ": " + e.getMessage(),
                    e);
        }
        cancelHeartbeat(heartbeat);

        long durationMs = System.currentTimeMillis() - callStart;
        recordSuccess(
                reserveId,
                request.getTenantId(),
                request.getUserId(),
                request.getProjectId(),
                request.getProcessId(),
                resolved.alias(),
                config,
                MODALITY_MUSIC,
                callStart,
                durationMs,
                durationSeconds,
                cost);
        bookUsage(
                request.getTenantId(),
                request.getProjectId(),
                request.getProcessId(),
                config,
                cost,
                UsageOutcome.SUCCESS,
                durationMs);

        return GenerateMusicResult.builder()
                .path(committed.getPath())
                .mimeType(committed.getMimeType() == null ? "audio/mpeg" : committed.getMimeType())
                .sizeBytes(committed.getSize())
                .modelUsed(config.fullName())
                .durationMs(durationMs)
                .costUsd(cost)
                .title(committed.getTitle())
                .build();
    }

    // ──────────────────── shared: validation / gating ────────────────────

    private static void validateScope(String tenantId, @Nullable String required, String requiredName) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (requiredName != null && (required == null || required.isBlank())) {
            throw new IllegalArgumentException(requiredName + " is required");
        }
    }

    private void ensureEnabled(
            String modality,
            String settingKey,
            String tenantId,
            @Nullable String projectId,
            @Nullable String processId) {
        boolean global = settingService.getBooleanValueCascade(tenantId, projectId, processId, SETTING_ENABLED, true);
        if (!global) {
            throw new HotblackException(
                    HotblackException.Reason.DISABLED, "Audio is disabled in this scope (" + SETTING_ENABLED + ")");
        }
        boolean enabled = settingService.getBooleanValueCascade(tenantId, projectId, processId, settingKey, true);
        if (!enabled) {
            throw new HotblackException(
                    HotblackException.Reason.DISABLED,
                    "Audio modality '" + modality + "' is disabled in this scope (" + settingKey + ")");
        }
    }

    /** Alias → resolved (protocol, instance, model) plus the alias label. */
    private record ResolvedModel(String alias, AiModelResolver.Resolved resolved) {}

    private ResolvedModel resolveModel(
            String alias, String modality, String tenantId, @Nullable String projectId, @Nullable String processId) {
        try {
            AiModelResolver.Resolved resolved = modelResolver.resolveOrDefault(alias, tenantId, projectId, processId);
            return new ResolvedModel(alias, resolved);
        } catch (RuntimeException e) {
            throw new HotblackException(
                    HotblackException.Reason.INVALID_CHOICE,
                    "Cannot resolve model alias '" + alias + "' for " + modality + ": " + e.getMessage(),
                    e);
        }
    }

    /**
     * Validate an explicit voice against the catalog voice list (when
     * published) and its language fit. A blank voice is fine — the
     * provider then uses its model default.
     */
    private static @Nullable String validateVoice(
            @Nullable String voice, @Nullable String language, TtsModelInfo modelInfo) {
        if (voice == null || voice.isBlank()) {
            return null;
        }
        if (!modelInfo.supportedVoices().isEmpty()) {
            for (TtsModelInfo.Voice candidate : modelInfo.supportedVoices()) {
                if (candidate.id().equalsIgnoreCase(voice)) {
                    if (!candidate.matchesLanguage(language)) {
                        throw new HotblackException(
                                HotblackException.Reason.UNSUPPORTED_LANGUAGE,
                                "Voice '" + candidate.id() + "' does not fit language '" + language
                                        + "' — see audio_voices for voices with a matching locale");
                    }
                    return candidate.id();
                }
            }
            throw new HotblackException(
                    HotblackException.Reason.INVALID_CHOICE,
                    "Voice '" + voice + "' is not published by " + modelInfo.provider() + ":" + modelInfo.modelName()
                            + " — see audio_voices for the list");
        }
        return voice;
    }

    /**
     * The committed output format. An explicit {@code requested} value is a
     * gate: it must be one of the standard formats <i>and</i> servable by the
     * model. Without one, the configured default is a preference — when the
     * model does not serve it, the first servable standard format wins (mp3
     * first, wav second), because one scope holds many models and a pcm-only
     * TTS must still work out of the box.
     */
    private String resolveFormat(
            @Nullable String requested,
            Set<String> modelFormats,
            String modelLabel,
            String tenantId,
            @Nullable String projectId,
            @Nullable String processId) {
        if (requested != null && !requested.isBlank()) {
            return requireOutputFormat(requested.trim().toLowerCase(Locale.ROOT), modelFormats, modelLabel);
        }
        String configured =
                settingService.getStringValueCascade(tenantId, projectId, processId, SETTING_DEFAULT_FORMAT);
        String chosen = chooseOutputFormat(modelFormats, configured);
        if (chosen == null) {
            throw new HotblackException(
                    HotblackException.Reason.UNSUPPORTED_FORMAT,
                    "Model " + modelLabel + " serves no output format Vance can commit — it serves " + modelFormats);
        }
        return chosen;
    }

    private static String requireOutputFormat(String format, Set<String> modelFormats, String modelLabel) {
        if (!format.equals("mp3") && !format.equals("wav")) {
            throw new HotblackException(
                    HotblackException.Reason.UNSUPPORTED_FORMAT,
                    "Unsupported output format '" + format + "' — use mp3 or wav");
        }
        if (!acceptsAudioFormat(modelFormats, format)) {
            throw new HotblackException(
                    HotblackException.Reason.UNSUPPORTED_FORMAT,
                    "Model " + modelLabel + " does not serve output format '" + format + "' — it serves "
                            + modelFormats);
        }
        return format;
    }

    /**
     * The implicit output format: the configured default when the model
     * serves it, else the first servable standard format (mp3 preferred, wav
     * second). {@code null} when the model serves none of them.
     */
    static @Nullable String chooseOutputFormat(Set<String> modelFormats, @Nullable String configured) {
        if (configured != null && !configured.isBlank()) {
            String candidate = configured.trim().toLowerCase(Locale.ROOT);
            if (acceptsAudioFormat(modelFormats, candidate)) {
                return candidate;
            }
        }
        for (String candidate : new String[] {"mp3", "wav"}) {
            if (acceptsAudioFormat(modelFormats, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Whether the model can deliver {@code format}. {@code pcm} is raw
     * samples without a container — providers wrap them into a WAV document
     * ({@code PcmWav}) and {@code AudioSource} maps {@code audio/pcm} to
     * {@code wav}, so the two spellings are one delivery form.
     */
    static boolean acceptsAudioFormat(Set<String> modelFormats, String format) {
        String wanted = format.toLowerCase(Locale.ROOT);
        for (String supported : modelFormats) {
            String s = supported.toLowerCase(Locale.ROOT);
            if (s.equals(wanted)) {
                return true;
            }
            if (pcmForm(s) && pcmForm(wanted)) {
                return true;
            }
        }
        return false;
    }

    private static boolean pcmForm(String format) {
        return "wav".equals(format) || "pcm".equals(format);
    }

    // ──────────────────── shared: title + path ────────────────────

    private record TitleResolution(@Nullable String title, String slug) {}

    private TitleResolution resolveTitleAndSlug(
            String tenantId,
            @Nullable String userId,
            @Nullable String projectId,
            @Nullable String processId,
            @Nullable String explicit,
            String prompt) {
        if (explicit != null && !explicit.isBlank()) {
            return new TitleResolution(explicit, slugify(explicit));
        }
        boolean autoTitle =
                settingService.getBooleanValueCascade(tenantId, projectId, processId, SETTING_AUTO_TITLE, true);
        if (!autoTitle) {
            return new TitleResolution(null, "audio");
        }
        try {
            Map<String, Object> reply = lightLlm.callForJson(LightLlmRequest.builder()
                    .recipeName(AUDIO_TITLE_RECIPE)
                    .userPrompt(prompt)
                    .pebbleVars(Map.of("prompt", prompt))
                    .tenantId(tenantId)
                    .projectId(projectId)
                    .processId(processId)
                    .build());
            String title = stringOrNull(reply.get("title"));
            String slug = stringOrNull(reply.get("slug"));
            if (slug == null || slug.isBlank()) {
                slug = slugify(title == null ? "audio" : title);
            }
            return new TitleResolution(title, sanitizeSlug(slug));
        } catch (LightLlmException e) {
            log.info(
                    "HotblackService: title generation failed for user '{}' ({}), falling back to 'audio' slug",
                    userId,
                    e.getMessage());
            return new TitleResolution(null, "audio");
        }
    }

    /** Caller-supplied path wins; otherwise {@code audio/<uuid8>-<slug>.<ext>}. */
    private static String resolvePath(@Nullable String requested, TitleResolution title, String format) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim();
        }
        String uuid8 = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return "audio/" + uuid8 + "-" + title.slug() + "." + ("wav".equals(format) ? "wav" : "mp3");
    }

    /** Lower-case kebab-case ASCII, max 30 chars — same rules as Fenchurch's slug. */
    static String slugify(String input) {
        if (input == null) return "audio";
        String normalized = java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(input.length());
        boolean lastHyphen = true;
        for (char c : normalized.toCharArray()) {
            if (Character.getType(c) == Character.NON_SPACING_MARK) {
                continue;
            }
            char lower = Character.toLowerCase(c);
            if ((lower >= 'a' && lower <= 'z') || (lower >= '0' && lower <= '9')) {
                out.append(lower);
                lastHyphen = false;
            } else if (!lastHyphen) {
                out.append('-');
                lastHyphen = true;
            }
            if (out.length() >= 30) break;
        }
        while (out.length() > 0 && out.charAt(out.length() - 1) == '-') {
            out.deleteCharAt(out.length() - 1);
        }
        return out.length() == 0 ? "audio" : out.toString();
    }

    static String sanitizeSlug(String input) {
        return slugify(input);
    }

    /** Duration wish + lyric language ride the prompt (native params where the wire has them). */
    private static String composeMusicPrompt(String prompt, @Nullable String language, int durationSeconds) {
        StringBuilder out = new StringBuilder(prompt.trim());
        if (durationSeconds > 0) {
            out.append("\n\n(Length: about ").append(durationSeconds).append(" seconds.)");
        }
        if (language != null && !language.isBlank()) {
            out.append("\n(Lyrics language: ").append(language).append(".)");
        }
        return out.toString();
    }

    // ──────────────────── shared: config + stream ────────────────────

    private AiAudioConfig buildConfig(
            ResolvedModel resolved,
            int modelTimeout,
            String tenantId,
            @Nullable String projectId,
            @Nullable String processId) {
        AiModelResolver.Resolved r = resolved.resolved();
        String apiKey = ChatBehaviorBuilder.resolveApiKey(
                r.provider(), r.providerInstance(), tenantId, projectId, processId, settingService);
        String baseUrl = ChatBehaviorBuilder.resolveBaseUrl(
                r.providerInstance(), tenantId, projectId, processId, settingService);
        int timeoutSeconds = resolveTimeout(tenantId, projectId, processId, modelTimeout);
        return new AiAudioConfig(r.provider(), r.providerInstance(), r.modelName(), apiKey, baseUrl, timeoutSeconds);
    }

    private int resolveTimeout(
            String tenantId, @Nullable String projectId, @Nullable String processId, int modelDefault) {
        String override = settingService.getStringValueCascade(tenantId, projectId, processId, SETTING_TIMEOUT);
        if (override != null && !override.isBlank()) {
            try {
                int parsed = Integer.parseInt(override.trim());
                if (parsed > 0) return parsed;
            } catch (NumberFormatException ignored) {
                log.warn(
                        "HotblackService: non-numeric '{}' setting '{}' — using model default",
                        SETTING_TIMEOUT,
                        override);
            }
        }
        return modelDefault > 0 ? modelDefault : 360;
    }

    private DocumentAudioDestinationStream openStream(
            GenerateSpeechRequest request, String path, @Nullable String title) {
        return openStream(request.getTenantId(), request.getUserId(), request.getProjectId(), path, title);
    }

    private DocumentAudioDestinationStream openStream(
            GenerateMusicRequest request, String path, @Nullable String title) {
        return openStream(request.getTenantId(), request.getUserId(), request.getProjectId(), path, title);
    }

    private DocumentAudioDestinationStream openStream(
            String tenantId, @Nullable String userId, @Nullable String projectId, String path, @Nullable String title) {
        DocumentAudioDestinationStream stream = new DocumentAudioDestinationStream(
                documentService,
                tenantId,
                resolveProjectId(projectId),
                path,
                userId,
                // User-initiated with a caller-controlled target path → carry the
                // acting user so the DocumentService chokepoint enforces WRITE.
                contextFactory.writeActor(tenantId, userId, path));
        if (title != null) {
            stream.setTitle(title);
        }
        return stream;
    }

    private AudioSource resolveAudioSource(TranscribeAudioRequest request) {
        boolean hasDoc =
                request.getDocumentId() != null && !request.getDocumentId().isBlank();
        boolean hasData = request.getAudioData() != null && request.getAudioData().length > 0;
        if (hasDoc == hasData) {
            throw new HotblackException(
                    HotblackException.Reason.INVALID_CHOICE,
                    "audio_transcribe needs exactly one audio source: documentId or audioData");
        }
        if (hasData) {
            String format =
                    request.getAudioFormat() == null || request.getAudioFormat().isBlank()
                            ? "wav"
                            : request.getAudioFormat().trim();
            return new AudioSource(request.getAudioData(), format, request.getAudioName());
        }
        // Tool path: resolve through the attachment pipeline — scope check,
        // MIME validation and size caps all live there, one authority for
        // chat attachments and transcription input.
        List<ResolvedAttachment> resolved;
        try {
            resolved = attachmentResolver.resolveAll(
                    List.of(new AttachmentRef(request.getDocumentId())),
                    request.getTenantId(),
                    resolveProjectId(request.getProjectId()));
        } catch (AttachmentException e) {
            throw new HotblackException(
                    HotblackException.Reason.INVALID_CHOICE, "Audio document rejected: " + e.getMessage(), e);
        }
        ResolvedAttachment att = resolved.get(0);
        if (!att.mimeType().startsWith("audio/")) {
            throw new HotblackException(
                    HotblackException.Reason.INVALID_CHOICE,
                    "Document '" + att.originalFilename() + "' is not an audio file (" + att.mimeType() + ")");
        }
        return new AudioSource(att.data(), AudioSource.formatForMime(att.mimeType()), att.originalFilename());
    }

    // ──────────────────── shared: quota + record ────────────────────

    private String reserve(String modality, GenerateSpeechRequest request) {
        return reserve(
                modality,
                request.getTenantId(),
                request.getUserId(),
                request.getProjectId(),
                request.getProcessId(),
                request.getAlias());
    }

    private String reserve(String modality, GenerateMusicRequest request) {
        return reserve(
                modality,
                request.getTenantId(),
                request.getUserId(),
                request.getProjectId(),
                request.getProcessId(),
                request.getAlias());
    }

    /**
     * Reserve a quota slot only AFTER all cheap validation has passed —
     * a reserve row counts against quota until finalized, so reserving
     * early would burn the tenant's limit on rejected requests (same
     * placement argument as Fenchurch's reserve).
     */
    private String reserve(
            String modality,
            String tenantId,
            @Nullable String userId,
            @Nullable String projectId,
            @Nullable String processId,
            @Nullable String alias) {
        AudioCallTracker.Reservation reservation =
                callTracker.reserve(modality, tenantId, userId, projectId, processId);
        if (reservation instanceof AudioCallTracker.Denied denied) {
            throw new HotblackException(
                    HotblackException.Reason.QUOTA_EXCEEDED,
                    denied.verdict().message() == null
                            ? "Quota exceeded for " + alias
                            : denied.verdict().message());
        }
        return ((AudioCallTracker.Granted) reservation).reserveId();
    }

    private void recordSuccess(
            String reserveId,
            String tenantId,
            @Nullable String userId,
            @Nullable String projectId,
            @Nullable String processId,
            String alias,
            AiAudioConfig config,
            String modality,
            long callStart,
            long durationMs,
            long inputUnits,
            @Nullable Double costUsd) {
        AudioCallRecord record = AudioCallRecord.builder()
                .id(reserveId)
                .tenantId(tenantId)
                .accountId(userId)
                .projectId(projectId)
                .modality(modality)
                .modelUsed(config.fullName())
                .alias(alias)
                .costUsd(costUsd)
                .inputUnits(inputUnits)
                .outcome("success")
                .processId(processId)
                .at(Instant.ofEpochMilli(callStart))
                .durationMs(durationMs)
                .build();
        callTracker.recordCall(record);
    }

    private void recordFailure(
            String reserveId,
            String tenantId,
            @Nullable String userId,
            @Nullable String projectId,
            @Nullable String processId,
            String alias,
            AiAudioConfig config,
            String modality,
            long callStart,
            long inputUnits,
            Throwable cause) {
        AudioCallRecord record = AudioCallRecord.builder()
                .id(reserveId)
                .tenantId(tenantId)
                .accountId(userId)
                .projectId(projectId)
                .modality(modality)
                .modelUsed(config.fullName())
                .alias(alias)
                .inputUnits(inputUnits)
                .outcome(outcomeFromError(cause))
                .processId(processId)
                .at(Instant.ofEpochMilli(callStart))
                .durationMs(System.currentTimeMillis() - callStart)
                .build();
        callTracker.recordCall(record);
    }

    /**
     * Book the audio call into the usage ledger, alongside the quota
     * row. Two collections on purpose: {@code audio_call_records} counts
     * reservations for the quota gate, the ledger holds what was
     * actually spent. Currency is USD — the model infos declare USD.
     */
    private void bookUsage(
            String tenantId,
            @Nullable String projectId,
            @Nullable String processId,
            AiAudioConfig config,
            @Nullable Double costUsd,
            UsageOutcome outcome,
            long durationMs) {
        usageSink.onCall(
                new CallAttribution(
                        tenantId,
                        projectId,
                        /* sessionId */ null,
                        processId,
                        LlmUsageService.CALLER_HOTBLACK,
                        /* recipeName */ null),
                UsageMeasurement.audio(
                        config.providerInstance(),
                        config.provider(),
                        config.modelName(),
                        costUsd,
                        "USD",
                        outcome,
                        durationMs));
    }

    /**
     * Cost booked for a successful call: the vendor-reported
     * {@code costUsd} header (written by providers that read the real
     * price off the response) wins over the catalog estimate; null
     * stays null — an unpriced call is counted but not billed.
     */
    static @Nullable Double effectiveCostUsd(@Nullable Double estimate, DocumentDocument committed) {
        String reported =
                committed.getHeaders() == null ? null : committed.getHeaders().get("costUsd");
        if (reported != null && !reported.isBlank()) {
            try {
                return Double.parseDouble(reported.trim());
            } catch (NumberFormatException ignored) {
                log.warn("HotblackService: non-numeric costUsd header '{}'", reported);
            }
        }
        return estimate;
    }

    private static @Nullable Double estimateTtsCost(TtsModelInfo modelInfo, String text) {
        if (modelInfo.costPerChar() != null) {
            return modelInfo.costPerChar() * text.length();
        }
        // Per-second pricing needs the generated length, which only the
        // provider knows — unpriced rather than a guess.
        return null;
    }

    private static @Nullable Double estimateMusicCost(MusicModelInfo modelInfo, int durationSeconds) {
        if (modelInfo.costPerTrack() != null) {
            return modelInfo.costPerTrack();
        }
        if (modelInfo.costPerSecond() != null && durationSeconds > 0) {
            return modelInfo.costPerSecond() * durationSeconds;
        }
        return null;
    }

    // ──────────────────── shared: heartbeat / progress ────────────────────

    private @Nullable ScheduledFuture<?> startHeartbeat(
            @Nullable ThinkProcessDocument process, String what, long callStart, GenerateSpeechRequest request) {
        return startHeartbeat(
                process, what, callStart, request.getTenantId(), request.getProjectId(), request.getProcessId());
    }

    private @Nullable ScheduledFuture<?> startHeartbeat(
            @Nullable ThinkProcessDocument process, String what, long callStart, GenerateMusicRequest request) {
        return startHeartbeat(
                process, what, callStart, request.getTenantId(), request.getProjectId(), request.getProcessId());
    }

    private @Nullable ScheduledFuture<?> startHeartbeat(
            @Nullable ThinkProcessDocument process,
            String what,
            long callStart,
            String tenantId,
            @Nullable String projectId,
            @Nullable String processId) {
        if (process == null) return null;
        progressEmitter.emitStatus(process, StatusTag.WAITING, "Generating " + what + " …");
        int interval = readHeartbeatInterval(tenantId, projectId, processId);
        if (interval <= 0 || scheduler == null) return null;
        return scheduler.scheduleAtFixedRate(
                () -> {
                    long elapsedSec = (System.currentTimeMillis() - callStart) / 1000;
                    try {
                        progressEmitter.emitStatus(
                                process,
                                StatusTag.WAITING,
                                String.format(
                                        Locale.ROOT,
                                        "Generating %s … %d:%02d elapsed",
                                        what,
                                        elapsedSec / 60,
                                        elapsedSec % 60));
                    } catch (RuntimeException e) {
                        log.debug("HotblackService: heartbeat emit failed: {}", e.toString());
                    }
                },
                interval,
                interval,
                TimeUnit.SECONDS);
    }

    private int readHeartbeatInterval(String tenantId, @Nullable String projectId, @Nullable String processId) {
        String raw = settingService.getStringValueCascade(tenantId, projectId, processId, SETTING_HEARTBEAT);
        if (raw == null || raw.isBlank()) return DEFAULT_HEARTBEAT_INTERVAL_SECONDS;
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : DEFAULT_HEARTBEAT_INTERVAL_SECONDS;
        } catch (NumberFormatException e) {
            return DEFAULT_HEARTBEAT_INTERVAL_SECONDS;
        }
    }

    private static void cancelHeartbeat(@Nullable Future<?> heartbeat) {
        if (heartbeat == null) return;
        heartbeat.cancel(false);
    }

    // ──────────────────── shared: helpers ────────────────────

    private DocumentDocument requireCommitted(String tenantId, String projectId, String path) {
        return documentService
                .findByPath(tenantId, projectId, path)
                .orElseThrow(() -> new HotblackException(
                        HotblackException.Reason.PROVIDER_ERROR,
                        "Audio generation reported success but the document at " + path + " was not committed"));
    }

    private static String resolveProjectId(@Nullable String projectId) {
        return projectId == null ? "" : projectId;
    }

    private static String resolveProjectId(GenerateSpeechRequest request) {
        return resolveProjectId(request.getProjectId());
    }

    private static String resolveProjectId(GenerateMusicRequest request) {
        return resolveProjectId(request.getProjectId());
    }

    private static String resolveProjectId(TranscribeAudioRequest request) {
        return resolveProjectId(request.getProjectId());
    }

    private @Nullable ThinkProcessDocument loadProcess(@Nullable String processId) {
        if (processId == null || processId.isBlank()) {
            return null;
        }
        return thinkProcessService.findById(processId).orElse(null);
    }

    private static String outcomeFromError(Throwable cause) {
        if (cause instanceof AiAudioException e && e.isUnsupportedOp()) {
            return "invalid_choice";
        }
        String msg = cause.getMessage() == null ? "" : cause.getMessage().toLowerCase(Locale.ROOT);
        if (msg.contains("timeout") || msg.contains("timed out")) {
            return "timeout";
        }
        if (msg.contains("safety") || msg.contains("content") || msg.contains("policy")) {
            return "content_policy";
        }
        if (cause instanceof java.util.concurrent.CancellationException || msg.contains("cancel")) {
            return "cancelled";
        }
        return "provider_error";
    }

    private static HotblackException mapProviderError(AiAudioException cause, AiAudioConfig config) {
        if (cause.isUnsupportedOp()) {
            return new HotblackException(
                    HotblackException.Reason.INVALID_CHOICE,
                    cause.getMessage() + " (model " + config.fullName() + ")",
                    cause);
        }
        String msg = cause.getMessage() == null ? "" : cause.getMessage().toLowerCase(Locale.ROOT);
        HotblackException.Reason reason = HotblackException.Reason.PROVIDER_ERROR;
        if (msg.contains("timeout") || msg.contains("timed out")) {
            reason = HotblackException.Reason.TIMEOUT;
        } else if (msg.contains("safety") || msg.contains("content") || msg.contains("policy")) {
            reason = HotblackException.Reason.CONTENT_POLICY;
        }
        return new HotblackException(
                reason, "Audio call failed for " + config.fullName() + ": " + cause.getMessage(), cause);
    }

    private static @Nullable String stringOrNull(@Nullable Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isBlank() ? null : s;
    }
}
