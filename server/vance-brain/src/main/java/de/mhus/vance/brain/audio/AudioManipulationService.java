package de.mhus.vance.brain.audio;

import de.mhus.vance.api.progress.StatusTag;
import de.mhus.vance.brain.ai.audio.AudioMimeTypeSniffer;
import de.mhus.vance.brain.progress.ProgressEmitter;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.permission.WriteActor;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Pure-Java audio manipulation on existing document assets: loads a
 * source document from the {@link DocumentService}, applies an ffmpeg
 * operation via {@link FfmpegAudioEditor} and writes the bytes back —
 * either overwriting the source (document-versioning archives the
 * prior version) or creating a new document under a caller-supplied
 * target path.
 *
 * <p>Mirrors {@code ImageManipulationService} deliberately: same
 * request/result shape, same limits-cascade settings, same metric
 * naming and the same user-driven write through
 * {@code createOrReplaceBinary} with a {@link WriteActor} built from
 * the acting user. Local and deterministic — no provider, no quota,
 * no call record (report: {@code planning/audio-edit-tools.md}).
 *
 * <p>Output format of trim/mix/concat is the source's own format when
 * it is already mp3 or wav, mp3 otherwise; {@code convert} picks
 * explicitly. The written mime type is always sniffed from the bytes
 * — never trusted from the source document.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AudioManipulationService {

    public static final String SETTING_ENABLED = "audio.tools.enabled";
    public static final String SETTING_MAX_INPUT_BYTES = "audio.tools.max_input_bytes";
    public static final String SETTING_MAX_DURATION_SECONDS = "audio.tools.max_duration_seconds";
    public static final String SETTING_MAX_OUTPUT_BYTES = "audio.tools.max_output_bytes";

    public static final long DEFAULT_MAX_INPUT_BYTES = 50_000_000L;
    public static final int DEFAULT_MAX_DURATION_SECONDS = 14_400;
    public static final long DEFAULT_MAX_OUTPUT_BYTES = 50_000_000L;

    /** Channels accepted by {@code convert}: mono and stereo. */
    private static final List<Integer> CONVERT_CHANNELS = List.of(1, 2);

    private final DocumentService documentService;
    private final SettingService settingService;
    private final ProgressEmitter progressEmitter;
    private final ThinkProcessService thinkProcessService;
    private final MetricService metricService;
    private final de.mhus.vance.brain.permission.SecurityContextFactory contextFactory;
    private final FfmpegAudioEditor editor;

    // ─────────────────── Public op surface ───────────────────

    /** Probe metadata of an existing audio document. Writes nothing. */
    public AudioProbeResult probe(ProbeRequest req) {
        validateScope(req.getTenantId(), refOf("path", "documentId", req.getPath(), req.getDocumentId()));
        ensureEnabled(req.getTenantId(), req.getProjectId(), req.getProcessId());
        Limits limits = readLimits(req.getTenantId(), req.getProjectId(), req.getProcessId());
        long startMs = System.currentTimeMillis();

        SourceDoc source =
                loadSource(req.getTenantId(), req.getProjectId(), req.getPath(), req.getDocumentId(), limits);
        FfmpegAudioEditor.ProbeData data = editor.probe(source.bytes());

        recordOutcome("audio_info", "success", startMs);
        return new AudioProbeResult(
                source.doc().getPath(),
                source.mime(),
                data.format(),
                data.codec(),
                data.channels(),
                data.sampleRate(),
                source.bytes().length,
                data.durationSeconds());
    }

    /** Cut a window out of the source, optionally faded. */
    public AudioOpResult trim(TrimRequest req) {
        validateScope(req.getTenantId(), refOf("path", "documentId", req.getPath(), req.getDocumentId()));
        ensureEnabled(req.getTenantId(), req.getProjectId(), req.getProcessId());
        if (req.getStartSeconds() < 0) {
            throw parameterInvalid("startSeconds must be >= 0, got " + req.getStartSeconds());
        }
        if (req.getEndSeconds() != null && req.getEndSeconds() <= req.getStartSeconds()) {
            throw parameterInvalid(
                    "endSeconds (" + req.getEndSeconds() + ") must be > startSeconds (" + req.getStartSeconds() + ")");
        }
        if (req.getFadeInSeconds() < 0 || req.getFadeOutSeconds() < 0) {
            throw parameterInvalid("fadeInSeconds/fadeOutSeconds must be >= 0");
        }
        Limits limits = readLimits(req.getTenantId(), req.getProjectId(), req.getProcessId());
        long startMs = System.currentTimeMillis();

        SourceDoc source =
                loadSource(req.getTenantId(), req.getProjectId(), req.getPath(), req.getDocumentId(), limits);
        Double sourceDuration = ensureDurationWithinLimit("audio_trim", startMs, source, limits);

        double available = req.getEndSeconds() != null
                ? req.getEndSeconds() - req.getStartSeconds()
                : (sourceDuration == null ? Double.MAX_VALUE : sourceDuration - req.getStartSeconds());
        if (req.getFadeOutSeconds() >= available) {
            throw parameterInvalid("fadeOutSeconds (" + req.getFadeOutSeconds()
                    + ") must be smaller than the cut length (" + available + ")");
        }

        emitInitialStatus(req.getProcessId(), "audio_trim");
        String outputFormat = outputFormatOf(source.mime(), null);
        byte[] out = editor.trim(
                source.bytes(),
                req.getStartSeconds(),
                req.getEndSeconds(),
                req.getFadeInSeconds(),
                req.getFadeOutSeconds(),
                outputFormat);

        return finish(
                "audio_trim",
                startMs,
                req.getTenantId(),
                req.getProjectId(),
                req.getUserId(),
                source,
                req.getTargetPath(),
                out,
                outputFormat);
    }

    /** Mix the overlay onto the base (narration + music bed). */
    public AudioOpResult mix(MixRequest req) {
        validateScope(req.getTenantId(), refOf("path", "documentId", req.getPath(), req.getDocumentId()));
        ensureEnabled(req.getTenantId(), req.getProjectId(), req.getProcessId());
        refOf("overlayPath", "overlayDocumentId", req.getOverlayPath(), req.getOverlayDocumentId());
        if (req.getBaseGain() < 0 || req.getOverlayGain() < 0) {
            throw parameterInvalid("baseGain/overlayGain must be >= 0");
        }
        if (req.getOverlayAtSeconds() < 0) {
            throw parameterInvalid("overlayAtSeconds must be >= 0, got " + req.getOverlayAtSeconds());
        }
        if (req.getFadeOutSeconds() < 0) {
            throw parameterInvalid("fadeOutSeconds must be >= 0");
        }
        Limits limits = readLimits(req.getTenantId(), req.getProjectId(), req.getProcessId());
        long startMs = System.currentTimeMillis();

        SourceDoc base = loadSource(req.getTenantId(), req.getProjectId(), req.getPath(), req.getDocumentId(), limits);
        SourceDoc overlay = loadSource(
                req.getTenantId(), req.getProjectId(), req.getOverlayPath(), req.getOverlayDocumentId(), limits);
        ensureDurationWithinLimit("audio_mix", startMs, base, limits);
        ensureDurationWithinLimit("audio_mix", startMs, overlay, limits);

        emitInitialStatus(req.getProcessId(), "audio_mix");
        String outputFormat = outputFormatOf(base.mime(), null);
        byte[] out = editor.mix(
                base.bytes(),
                overlay.bytes(),
                req.getBaseGain(),
                req.getOverlayGain(),
                req.getOverlayAtSeconds(),
                req.isDuckOverlay(),
                req.getFadeOutSeconds(),
                outputFormat);

        return finish(
                "audio_mix",
                startMs,
                req.getTenantId(),
                req.getProjectId(),
                req.getUserId(),
                base,
                req.getTargetPath(),
                out,
                outputFormat);
    }

    /** Join clips in order into one document. */
    public AudioOpResult concat(ConcatRequest req) {
        List<String> refs = resolveClipRefs(req);
        validateScope(req.getTenantId(), refs.get(0));
        String targetPath = requireTargetPath(req.getTargetPath());
        ensureEnabled(req.getTenantId(), req.getProjectId(), req.getProcessId());
        if (req.getCrossfadeSeconds() < 0) {
            throw parameterInvalid("crossfadeSeconds must be >= 0");
        }
        Limits limits = readLimits(req.getTenantId(), req.getProjectId(), req.getProcessId());
        long startMs = System.currentTimeMillis();

        boolean byPath = req.getPaths() != null && !req.getPaths().isEmpty();
        List<SourceDoc> clips = new ArrayList<>();
        for (String ref : refs) {
            clips.add(
                    byPath
                            ? loadSource(req.getTenantId(), req.getProjectId(), ref, null, limits)
                            : loadSource(req.getTenantId(), req.getProjectId(), null, ref, limits));
        }
        for (SourceDoc clip : clips) {
            ensureDurationWithinLimit("audio_concat", startMs, clip, limits);
        }

        String outputFormat = outputFormatOf(clips.get(0).mime(), null);
        ensureTargetIsNotAClip(targetPath, clips);
        emitInitialStatus(req.getProcessId(), "audio_concat");
        List<byte[]> clipBytes = clips.stream().map(SourceDoc::bytes).toList();
        byte[] out = editor.concat(clipBytes, req.getCrossfadeSeconds(), outputFormat);

        SourceDoc base = clips.get(0);
        return finish(
                "audio_concat",
                startMs,
                req.getTenantId(),
                req.getProjectId(),
                req.getUserId(),
                base,
                targetPath,
                out,
                outputFormat);
    }

    /** Re-encode into another format, optionally resampled/normalised. */
    public AudioOpResult convert(ConvertRequest req) {
        validateScope(req.getTenantId(), refOf("path", "documentId", req.getPath(), req.getDocumentId()));
        ensureEnabled(req.getTenantId(), req.getProjectId(), req.getProcessId());
        String format = req.getFormat() == null ? "" : req.getFormat().toLowerCase(Locale.ROOT);
        if (!"mp3".equals(format) && !"wav".equals(format)) {
            throw new AudioManipulationException(
                    AudioManipulationException.Reason.FORMAT_UNSUPPORTED,
                    "format must be 'mp3' or 'wav', got '" + req.getFormat() + "'");
        }
        if (req.getSampleRate() != null && req.getSampleRate() <= 0) {
            throw parameterInvalid("sampleRate must be > 0, got " + req.getSampleRate());
        }
        if (req.getChannels() != null && !CONVERT_CHANNELS.contains(req.getChannels())) {
            throw parameterInvalid("channels must be 1 (mono) or 2 (stereo), got " + req.getChannels());
        }
        if (req.getBitrateKbps() != null && (req.getBitrateKbps() < 32 || req.getBitrateKbps() > 320)) {
            throw parameterInvalid("bitrateKbps must be between 32 and 320, got " + req.getBitrateKbps());
        }
        if (req.getBitrateKbps() != null && "wav".equals(format)) {
            throw parameterInvalid("bitrateKbps applies to mp3 only");
        }
        Limits limits = readLimits(req.getTenantId(), req.getProjectId(), req.getProcessId());
        long startMs = System.currentTimeMillis();

        SourceDoc source =
                loadSource(req.getTenantId(), req.getProjectId(), req.getPath(), req.getDocumentId(), limits);
        ensureDurationWithinLimit("audio_convert", startMs, source, limits);

        emitInitialStatus(req.getProcessId(), "audio_convert");
        byte[] out = editor.convert(
                source.bytes(),
                req.getSampleRate(),
                req.getChannels(),
                req.getBitrateKbps(),
                req.isNormalize(),
                format);

        return finish(
                "audio_convert",
                startMs,
                req.getTenantId(),
                req.getProjectId(),
                req.getUserId(),
                source,
                req.getTargetPath(),
                out,
                format);
    }

    // ─────────────────── Shared pipeline ───────────────────

    /**
     * Post-op half of the pipeline: size + duration check, mime
     * sniffing, target resolution, user-driven write, metric record.
     */
    private AudioOpResult finish(
            String opName,
            long startMs,
            String tenantId,
            @Nullable String projectId,
            @Nullable String userId,
            SourceDoc source,
            @Nullable String targetPath,
            byte[] out,
            String outputFormat) {
        long start = System.currentTimeMillis();
        if (out.length > readMaxOutputBytes(tenantId, projectId, null)) {
            recordOutcome(opName, "limit_exceeded", startMs);
            throw new AudioManipulationException(
                    AudioManipulationException.Reason.LIMIT_EXCEEDED,
                    "Output size " + out.length + " bytes exceeds " + readMaxOutputBytes(tenantId, projectId, null)
                            + " (" + SETTING_MAX_OUTPUT_BYTES + ")");
        }
        String mime = AudioMimeTypeSniffer.sniff(out, fallbackMime(outputFormat));
        String effectiveProject = projectId == null ? "" : projectId;
        String effectiveTarget = resolveTarget(source.doc().getPath(), targetPath);
        ensureTargetIsWritable(
                opName, startMs, tenantId, effectiveProject, source.doc().getPath(), effectiveTarget);

        DocumentDocument written = documentService.createOrReplaceBinary(
                tenantId,
                effectiveProject,
                effectiveTarget,
                out,
                mime,
                null,
                null,
                null,
                userId,
                contextFactory.writeActor(tenantId, userId, effectiveTarget));

        Double durationSeconds = editor.probe(out).durationSeconds();
        long durationMs = start - startMs;
        recordOutcome(opName, "success", startMs);
        return new AudioOpResult(
                written.getPath(),
                written.getMimeType() == null ? mime : written.getMimeType(),
                written.getSize() > 0 ? written.getSize() : out.length,
                durationSeconds,
                durationMs);
    }

    /**
     * ffmpeg work is bounded by duration, not only by bytes (a 200 MB
     * flac of an hour is cheap; a tiny 12-hour file is not). Probed
     * before the op runs; {@code null} duration (exotic container)
     * passes and is caught by the run timeout instead.
     */
    private @Nullable Double ensureDurationWithinLimit(String opName, long startMs, SourceDoc source, Limits limits) {
        Double duration = editor.probe(source.bytes()).durationSeconds();
        if (duration != null && duration > limits.maxDurationSeconds()) {
            recordOutcome(opName, "limit_exceeded", startMs);
            throw new AudioManipulationException(
                    AudioManipulationException.Reason.LIMIT_EXCEEDED,
                    "Audio is " + duration + "s long; limit is " + limits.maxDurationSeconds() + "s ("
                            + SETTING_MAX_DURATION_SECONDS + ")");
        }
        return duration;
    }

    private SourceDoc loadSource(
            String tenantId,
            @Nullable String projectId,
            @Nullable String path,
            @Nullable String documentId,
            Limits limits) {
        String effectiveProject = projectId == null ? "" : projectId;
        Optional<DocumentDocument> found = path != null && !path.isBlank()
                ? documentService.findByPath(tenantId, effectiveProject, path)
                : documentService.findById(documentId == null ? "" : documentId);
        DocumentDocument doc = found.orElseThrow(() -> new AudioManipulationException(
                AudioManipulationException.Reason.SOURCE_NOT_FOUND,
                "No audio document at " + (path != null ? "path '" + path + "'" : "id '" + documentId + "'")));

        String mime = doc.getMimeType() == null ? "" : doc.getMimeType().toLowerCase(Locale.ROOT);
        if (!mime.startsWith("audio/")) {
            throw new AudioManipulationException(
                    AudioManipulationException.Reason.NOT_AUDIO,
                    "Document '" + doc.getPath() + "' is not audio (mimeType="
                            + (doc.getMimeType() == null ? "null" : doc.getMimeType()) + ")");
        }
        if (doc.getSize() > limits.maxInputBytes()) {
            throw new AudioManipulationException(
                    AudioManipulationException.Reason.LIMIT_EXCEEDED,
                    "Source audio is " + doc.getSize() + " bytes; limit is " + limits.maxInputBytes() + " ("
                            + SETTING_MAX_INPUT_BYTES + ")");
        }
        return new SourceDoc(doc, readBytes(doc, limits), mime);
    }

    private byte[] readBytes(DocumentDocument source, Limits limits) {
        try (InputStream in = documentService.loadContent(source)) {
            byte[] bytes = in.readAllBytes();
            if (bytes.length > limits.maxInputBytes()) {
                throw new AudioManipulationException(
                        AudioManipulationException.Reason.LIMIT_EXCEEDED,
                        "Source audio is " + bytes.length + " bytes; limit is " + limits.maxInputBytes() + " ("
                                + SETTING_MAX_INPUT_BYTES + ")");
            }
            return bytes;
        } catch (IOException e) {
            throw new AudioManipulationException(
                    AudioManipulationException.Reason.PROCESSING_ERROR,
                    "Failed to read source audio bytes: " + e.getMessage(),
                    e);
        }
    }

    /** Loaded source: document, its bytes and the normalised mime. */
    private record SourceDoc(DocumentDocument doc, byte[] bytes, String mime) {}

    /**
     * Output format for trim/mix/concat: the source's own format when
     * it is already mp3 or wav (no pointless re-encode), mp3 for
     * everything else (webm/ogg/m4a uploads land as a universally
     * playable file).
     */
    private static String outputFormatOf(String sourceMime, @Nullable String explicit) {
        if (explicit != null) {
            return explicit;
        }
        return switch (sourceMime) {
            case "audio/mpeg", "audio/mp3" -> "mp3";
            case "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave" -> "wav";
            default -> "mp3";
        };
    }

    private static String fallbackMime(String outputFormat) {
        return "wav".equals(outputFormat) ? "audio/wav" : "audio/mpeg";
    }

    private static String resolveTarget(String sourcePath, @Nullable String targetPath) {
        if (targetPath == null || targetPath.isBlank()) {
            return sourcePath;
        }
        String trimmed = targetPath.trim();
        return trimmed.equals(sourcePath) ? sourcePath : trimmed;
    }

    private void ensureTargetIsWritable(
            String opName, long startMs, String tenantId, String projectId, String sourcePath, String effectiveTarget) {
        if (effectiveTarget.equals(sourcePath)) {
            return;
        }
        Optional<DocumentDocument> existing = documentService.findByPath(tenantId, projectId, effectiveTarget);
        if (existing.isEmpty()) {
            return;
        }
        String existingMime = existing.get().getMimeType();
        if (existingMime == null || !existingMime.toLowerCase(Locale.ROOT).startsWith("audio/")) {
            recordOutcome(opName, "target_blocked", startMs);
            throw new AudioManipulationException(
                    AudioManipulationException.Reason.TARGET_BLOCKED,
                    "Target '" + effectiveTarget + "' exists but is not audio (mimeType=" + existingMime + ")");
        }
    }

    /** A concat target may never be one of the clips — that would
     *  overwrite an input the operation is still reading. */
    private void ensureTargetIsNotAClip(String targetPath, List<SourceDoc> clips) {
        for (SourceDoc clip : clips) {
            if (clip.doc().getPath().equals(targetPath.trim())) {
                throw new AudioManipulationException(
                        AudioManipulationException.Reason.TARGET_BLOCKED,
                        "targetPath '" + targetPath + "' is one of the concat clips");
            }
        }
    }

    /** Exactly one of paths/documentIds with at least two entries. */
    private static List<String> resolveClipRefs(ConcatRequest req) {
        List<String> paths = req.getPaths() == null
                ? List.of()
                : req.getPaths().stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
        List<String> ids = req.getDocumentIds() == null
                ? List.of()
                : req.getDocumentIds().stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
        boolean both = !paths.isEmpty() && !ids.isEmpty();
        boolean neither = paths.isEmpty() && ids.isEmpty();
        if (both || neither) {
            throw parameterInvalid("exactly one of paths / documentIds is required for audio_concat");
        }
        List<String> refs = paths.isEmpty() ? ids : paths;
        if (refs.size() < 2) {
            throw parameterInvalid("audio_concat needs at least 2 clips, got " + refs.size());
        }
        return refs;
    }

    /** Exactly one of the labelled pair must be set; returns the
     *  chosen reference. */
    private static String refOf(String pathLabel, String idLabel, @Nullable String path, @Nullable String documentId) {
        if (path != null && !path.isBlank() && (documentId == null || documentId.isBlank())) {
            return path.trim();
        }
        if (documentId != null && !documentId.isBlank() && (path == null || path.isBlank())) {
            return documentId.trim();
        }
        throw parameterInvalid("exactly one of " + pathLabel + " / " + idLabel + " is required");
    }

    /** concat never overwrites its clips — the target is mandatory. */
    private static String requireTargetPath(@Nullable String targetPath) {
        if (targetPath == null || targetPath.isBlank()) {
            throw parameterInvalid("targetPath is required for audio_concat (concat never overwrites its clips)");
        }
        return targetPath.trim();
    }

    private void emitInitialStatus(@Nullable String processId, String opName) {
        if (processId == null || processId.isBlank()) {
            return;
        }
        ThinkProcessDocument process = thinkProcessService.findById(processId).orElse(null);
        if (process == null) {
            return;
        }
        try {
            progressEmitter.emitStatus(process, StatusTag.WAITING, opName.replace('_', ' ') + " …");
        } catch (RuntimeException e) {
            log.debug("AudioManipulationService: status emit failed: {}", e.toString());
        }
    }

    private void recordOutcome(String opName, String outcome, long startMs) {
        long duration = System.currentTimeMillis() - startMs;
        try {
            metricService
                    .counter("vance.audio.tools.calls", "tool", opName, "outcome", outcome)
                    .increment();
            metricService.timer("vance.audio.tools.duration", "tool", opName).record(Duration.ofMillis(duration));
        } catch (RuntimeException e) {
            log.debug("AudioManipulationService: metric record failed: {}", e.toString());
        }
    }

    // ─────────────────── Validation / gating ───────────────────

    private static void validateScope(String tenantId, @Nullable String ref) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (ref == null || ref.isBlank()) {
            throw parameterInvalid("'path' or 'documentId' is required");
        }
    }

    private void ensureEnabled(String tenantId, @Nullable String projectId, @Nullable String processId) {
        boolean enabled = settingService.getBooleanValueCascade(tenantId, projectId, processId, SETTING_ENABLED, true);
        if (!enabled) {
            throw new AudioManipulationException(
                    AudioManipulationException.Reason.DISABLED,
                    "Audio manipulation tools are disabled in this scope (" + SETTING_ENABLED + " = false)");
        }
    }

    private static AudioManipulationException parameterInvalid(String message) {
        return new AudioManipulationException(AudioManipulationException.Reason.PARAMETER_INVALID, message);
    }

    // ─────────────────── Settings ───────────────────

    private record Limits(long maxInputBytes, int maxDurationSeconds, long maxOutputBytes) {}

    private Limits readLimits(String tenantId, @Nullable String projectId, @Nullable String processId) {
        return new Limits(
                longSetting(tenantId, projectId, processId, SETTING_MAX_INPUT_BYTES, DEFAULT_MAX_INPUT_BYTES),
                intSetting(tenantId, projectId, processId, SETTING_MAX_DURATION_SECONDS, DEFAULT_MAX_DURATION_SECONDS),
                longSetting(tenantId, projectId, processId, SETTING_MAX_OUTPUT_BYTES, DEFAULT_MAX_OUTPUT_BYTES));
    }

    private long readMaxOutputBytes(String tenantId, @Nullable String projectId, @Nullable String processId) {
        return longSetting(tenantId, projectId, processId, SETTING_MAX_OUTPUT_BYTES, DEFAULT_MAX_OUTPUT_BYTES);
    }

    private long longSetting(
            String tenantId, @Nullable String projectId, @Nullable String processId, String key, long defaultValue) {
        String raw = settingService.getStringValueCascade(tenantId, projectId, processId, key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            log.debug("AudioManipulationService: unparsable {} value '{}', using default", key, raw);
            return defaultValue;
        }
    }

    private int intSetting(
            String tenantId, @Nullable String projectId, @Nullable String processId, String key, int defaultValue) {
        String raw = settingService.getStringValueCascade(tenantId, projectId, processId, key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            log.debug("AudioManipulationService: unparsable {} value '{}', using default", key, raw);
            return defaultValue;
        }
    }
}
