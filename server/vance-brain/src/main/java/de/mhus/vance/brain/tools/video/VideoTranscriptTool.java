package de.mhus.vance.brain.tools.video;

import de.mhus.vance.api.progress.StatusTag;
import de.mhus.vance.brain.progress.ProgressEmitter;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import io.github.thoroldvix.api.Transcript;
import io.github.thoroldvix.api.TranscriptApiFactory;
import io.github.thoroldvix.api.TranscriptContent;
import io.github.thoroldvix.api.TranscriptList;
import io.github.thoroldvix.api.TranscriptRetrievalException;
import io.github.thoroldvix.api.YoutubeTranscriptApi;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Fetch the spoken-word transcript of a YouTube video. Two stages,
 * tried in order:
 *
 * <ol>
 *   <li><b>Captions</b> — pulled from YouTube's existing caption track
 *       (manual subtitles preferred, falling back to auto-generated)
 *       via the {@code youtube-transcript-api} library. ~Seconds, no
 *       audio download.</li>
 *   <li><b>ASR fallback</b> — only if captions are unavailable
 *       <i>and</i> the caller doesn't opt out: yt-dlp pulls the
 *       audio, then a bundled Python wrapper invokes
 *       {@code faster-whisper} for speech-to-text. Minutes; requires
 *       both binaries on the host PATH (the brain Dockerfile ships
 *       them).</li>
 * </ol>
 *
 * <p>Stage 1 and stage 2 each ping the user-progress channel
 * (`PROCESS_PROGRESS` status messages) so the user sees what's
 * happening rather than staring at a silent spinner while ASR
 * grinds.
 *
 * <p>YouTube only in this iteration. Vimeo / generic media URLs are
 * tracked in {@code planning/video-transcript-tool.md}.
 */
@Component
@Slf4j
public class VideoTranscriptTool implements Tool {

    /** Truncation budget for the returned text, in characters. */
    static final int MAX_TEXT_CHARS = 50_000;

    /** Default language preference when the caller doesn't specify. */
    private static final List<String> DEFAULT_LANGUAGES = List.of("en", "de");

    /** Default Whisper model when the caller doesn't specify. */
    private static final String DEFAULT_ASR_MODEL = "small";

    /** How often (in seconds of audio progress) we emit a progress ping
     *  during ASR. Streaming every fragment is too chatty; once per
     *  ~30 seconds of audio gives the user enough feedback without
     *  flooding the WS. */
    private static final double ASR_PROGRESS_INTERVAL_SEC = 30.0;

    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Pattern URL_PATH_ID = Pattern.compile(
            "/(?:embed|shorts|v|live)/([A-Za-z0-9_-]{11})");

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "url", Map.of(
                            "type", "string",
                            "description", "YouTube video URL "
                                    + "(youtube.com/watch?v=…, youtu.be/…, "
                                    + "shorts/…, embed/…) or a bare 11-char "
                                    + "video id."),
                    "language", Map.of(
                            "type", "string",
                            "description", "Optional preferred caption "
                                    + "language(s), comma-separated BCP-47 "
                                    + "codes (e.g. 'de', 'de,en'). First "
                                    + "available wins. Defaults to 'en,de'. "
                                    + "Also passed to the ASR fallback as "
                                    + "a hint; pass 'auto' to let Whisper "
                                    + "detect."),
                    "fallback", Map.of(
                            "type", "string",
                            "description", "What to do when no captions "
                                    + "are available. 'auto' (default) — "
                                    + "download audio and run Whisper ASR. "
                                    + "'captions' — error out instead of "
                                    + "doing ASR (cheap, deterministic). "
                                    + "'asr' — skip captions and go "
                                    + "straight to ASR."),
                    "asrModel", Map.of(
                            "type", "string",
                            "description", "Whisper model for the ASR "
                                    + "fallback. One of: tiny, base, "
                                    + "small (default), medium, "
                                    + "large-v3, large-v3-turbo. Bigger "
                                    + "is more accurate but slower."),
                    "timestamps", Map.of(
                            "type", "boolean",
                            "description", "If true, prefix each segment "
                                    + "with [hh:mm:ss]. Default false "
                                    + "(plain text — denser for the LLM).")),
            "required", List.of("url"));

    private final ThinkProcessService thinkProcessService;
    private final ProgressEmitter progressEmitter;
    private final YtDlpAudioDownloader audioDownloader;
    private final WhisperTranscriber whisperTranscriber;

    public VideoTranscriptTool(ThinkProcessService thinkProcessService,
                               ProgressEmitter progressEmitter,
                               YtDlpAudioDownloader audioDownloader,
                               WhisperTranscriber whisperTranscriber) {
        this.thinkProcessService = thinkProcessService;
        this.progressEmitter = progressEmitter;
        this.audioDownloader = audioDownloader;
        this.whisperTranscriber = whisperTranscriber;
    }

    @Override
    public String name() {
        return "video_transcript";
    }

    @Override
    public String description() {
        return "Fetch the spoken-word transcript of a YouTube video. "
                + "Tries existing captions first (manual / auto-generated, "
                + "seconds, no audio download). When the video has no "
                + "captions, falls back to ASR — downloads the audio "
                + "with yt-dlp and transcribes it with faster-whisper "
                + "(minutes; emits progress messages so the user sees "
                + "the long stage isn't stalled). Returns plain text "
                + "plus metadata (language, source=manual|asr-whisper-*, "
                + "durationSec, segmentCount, videoId). YouTube only. "
                + "Long videos are truncated past " + MAX_TEXT_CHARS
                + " characters — contentLength reports the full size. "
                + "Set fallback='captions' to disable the ASR stage if "
                + "you want a fast deterministic answer.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public Set<String> labels() {
        return Set.of("read-only");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String rawUrl = params == null ? null : asString(params.get("url"));
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new ToolException("'url' is required");
        }
        String videoId = extractVideoId(rawUrl);
        if (videoId == null) {
            throw new ToolException(
                    "Could not extract a YouTube video id from '"
                            + rawUrl + "'. Expected a youtube.com / "
                            + "youtu.be URL or a bare 11-char id.");
        }

        List<String> langs = parseLanguages(
                params == null ? null : asString(params.get("language")));
        boolean withTimestamps = asBoolean(
                params == null ? null : params.get("timestamps"), false);
        FallbackMode mode = FallbackMode.parse(
                params == null ? null : asString(params.get("fallback")));
        String asrModel = asString(
                params == null ? null : params.get("asrModel"));

        ThinkProcessDocument process = loadProcess(ctx);

        // ─── Stage 1: captions ─────────────────────────────────────
        if (mode != FallbackMode.ASR_ONLY) {
            emit(process, StatusTag.FETCH,
                    "Looking for captions on video " + videoId + "…");
            try {
                return fetchCaptions(videoId, langs, withTimestamps, ctx);
            } catch (NoCaptionsException e) {
                if (mode == FallbackMode.CAPTIONS_ONLY) {
                    throw new ToolException(
                            "No captions available for video '" + videoId
                                    + "' and fallback is set to 'captions'. "
                                    + "Set fallback='auto' to enable ASR.");
                }
                emit(process, StatusTag.INFO,
                        "No captions found — switching to ASR (Whisper).");
            }
        }

        // ─── Stage 2: ASR fallback ─────────────────────────────────
        return runAsrFallback(videoId, langs, withTimestamps,
                asrModel, ctx, process);
    }

    private Map<String, Object> fetchCaptions(String videoId,
                                              List<String> langs,
                                              boolean withTimestamps,
                                              ToolInvocationContext ctx) {
        YoutubeTranscriptApi api = TranscriptApiFactory.createDefault();
        try {
            TranscriptList list = api.listTranscripts(videoId);
            Transcript transcript = list.findTranscript(
                    langs.toArray(String[]::new));
            TranscriptContent content = transcript.fetch();

            String text = withTimestamps
                    ? formatWithTimestamps(content)
                    : formatPlain(content);
            int fullLength = text.length();
            boolean truncated = fullLength > MAX_TEXT_CHARS;
            String body = truncated ? text.substring(0, MAX_TEXT_CHARS) : text;

            int segments = content.getContent().size();
            double duration = computeDurationSec(content);

            log.info("VideoTranscriptTool tenant='{}' videoId='{}' "
                            + "stage=captions lang='{}' source={} "
                            + "segments={} bytes={}",
                    ctx.tenantId(), videoId, transcript.getLanguageCode(),
                    transcript.isGenerated() ? "asr-youtube" : "manual",
                    segments, fullLength);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("videoId", videoId);
            out.put("language", transcript.getLanguage());
            out.put("languageCode", transcript.getLanguageCode());
            out.put("source", transcript.isGenerated() ? "asr-youtube" : "manual");
            out.put("segmentCount", segments);
            out.put("durationSec", duration);
            out.put("contentLength", fullLength);
            out.put("truncated", truncated);
            out.put("text", body);
            return out;
        } catch (TranscriptRetrievalException e) {
            // We can't distinguish "no captions at all" from "all
            // requested languages absent" without poking the
            // TranscriptList ourselves — for our purposes both warrant
            // the ASR fallback. Bubble the original message in the
            // wrapped exception so the operator log keeps the detail.
            throw new NoCaptionsException(e.getMessage(), e);
        }
    }

    private Map<String, Object> runAsrFallback(String videoId,
                                               List<String> langs,
                                               boolean withTimestamps,
                                               @Nullable String asrModel,
                                               ToolInvocationContext ctx,
                                               ThinkProcessDocument process) {
        String effectiveModel = (asrModel == null || asrModel.isBlank())
                ? DEFAULT_ASR_MODEL : asrModel;
        // Use only the first explicit language hint (or null for auto).
        // Whisper takes one language, not a fallback list.
        String hint = langs.isEmpty() || "auto".equals(langs.get(0))
                ? null : langs.get(0);

        emit(process, StatusTag.FETCH,
                "Downloading audio for video " + videoId + " "
                        + "(yt-dlp)…");
        Path audio;
        try {
            audio = audioDownloader.download(videoId);
        } catch (ToolException e) {
            emit(process, StatusTag.INFO,
                    "Audio download failed: " + e.getMessage());
            throw e;
        }

        try {
            long sizeBytes = sizeQuiet(audio);
            emit(process, StatusTag.INFO,
                    String.format(Locale.ROOT,
                            "Audio ready (%.1f MB). Transcribing with "
                                    + "Whisper '%s'…",
                            sizeBytes / (1024.0 * 1024.0),
                            effectiveModel));

            // Throttled progress: one ping per ~30 audio-seconds.
            AtomicLong lastEmittedKey = new AtomicLong(-1);
            WhisperTranscriber.ProgressSink sink = (chunkEnd, duration) -> {
                long key = (long) (chunkEnd / ASR_PROGRESS_INTERVAL_SEC);
                if (key != lastEmittedKey.get()) {
                    lastEmittedKey.set(key);
                    String msg = duration > 0
                            ? String.format(Locale.ROOT,
                                    "Transcribed %s / %s (%d%%)",
                                    formatHhmmss(chunkEnd),
                                    formatHhmmss(duration),
                                    (int) Math.min(100,
                                            Math.round(chunkEnd / duration * 100)))
                            : String.format(Locale.ROOT,
                                    "Transcribed %s",
                                    formatHhmmss(chunkEnd));
                    emit(process, StatusTag.INFO, msg);
                }
            };

            WhisperTranscriber.Result result;
            try {
                result = whisperTranscriber.transcribe(
                        audio, effectiveModel, hint, sink);
            } catch (ToolException e) {
                emit(process, StatusTag.INFO,
                        "Transcription failed: " + e.getMessage());
                throw e;
            }

            String text = withTimestamps
                    ? formatAsrWithTimestamps(result)
                    : formatAsrPlain(result);
            int fullLength = text.length();
            boolean truncated = fullLength > MAX_TEXT_CHARS;
            String body = truncated ? text.substring(0, MAX_TEXT_CHARS) : text;

            emit(process, StatusTag.INFO,
                    String.format(Locale.ROOT,
                            "Done — %d segments, %.1f s of audio, "
                                    + "%.1f s of compute.",
                            result.segments().size(),
                            result.durationSec(),
                            result.elapsedSec()));

            log.info("VideoTranscriptTool tenant='{}' videoId='{}' "
                            + "stage=asr model='{}' lang='{}' segments={} "
                            + "audioSec={} elapsedSec={}",
                    ctx.tenantId(), videoId, effectiveModel,
                    result.language(), result.segments().size(),
                    result.durationSec(), result.elapsedSec());

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("videoId", videoId);
            out.put("language", result.language());
            out.put("languageCode", result.language());
            out.put("source", "asr-whisper-" + effectiveModel);
            out.put("segmentCount", result.segments().size());
            out.put("durationSec", result.durationSec());
            out.put("transcriptionSec", result.elapsedSec());
            out.put("contentLength", fullLength);
            out.put("truncated", truncated);
            out.put("text", body);
            return out;
        } finally {
            cleanupTempAudio(audio, videoId);
        }
    }

    /**
     * Delete the primary mp3 and any yt-dlp intermediates left in
     * {@code /tmp}. yt-dlp normally cleans up its own {@code .part}
     * and pre-conversion files, but a crash mid-download or
     * mid-ffmpeg-convert can strand them. The video-id-prefix glob
     * is safe because the id alphabet is constrained to
     * {@code [A-Za-z0-9_-]} by {@link #VIDEO_ID}.
     */
    private static void cleanupTempAudio(Path mp3Path, String videoId) {
        try {
            Files.deleteIfExists(mp3Path);
        } catch (IOException e) {
            log.warn("Could not delete temp audio {}: {}",
                    mp3Path, e.getMessage());
        }
        Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"));
        String prefix = "yt-" + videoId + ".";
        try (var stream = Files.newDirectoryStream(tmpDir,
                p -> p.getFileName().toString().startsWith(prefix))) {
            for (Path leftover : stream) {
                try {
                    Files.deleteIfExists(leftover);
                    log.debug("Cleaned up yt-dlp leftover {}", leftover);
                } catch (IOException e) {
                    log.warn("Could not delete yt-dlp leftover {}: {}",
                            leftover, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Could not scan for yt-dlp leftovers in {}: {}",
                    tmpDir, e.getMessage());
        }
    }

    /** Best-effort process lookup. Returns {@code null} when the
     *  tool is invoked outside a process scope (e.g. admin flow);
     *  the progress emit then silently skips. */
    private @Nullable ThinkProcessDocument loadProcess(ToolInvocationContext ctx) {
        if (ctx == null || ctx.processId() == null) return null;
        Optional<ThinkProcessDocument> opt = thinkProcessService.findById(ctx.processId());
        return opt.orElse(null);
    }

    private void emit(@Nullable ThinkProcessDocument process,
                      StatusTag tag, String text) {
        if (process == null) return;
        progressEmitter.emitStatus(process, tag, text);
    }

    private static long sizeQuiet(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0L;
        }
    }

    // ── Helpers (package-private for unit tests) ────────────────────

    /**
     * Pull the canonical 11-char video id out of common YouTube
     * URL shapes, or accept a bare id verbatim. Returns {@code null}
     * when nothing usable can be extracted.
     */
    static @Nullable String extractVideoId(String input) {
        if (input == null) return null;
        String s = input.trim();
        if (s.isEmpty()) return null;
        if (!s.contains("/") && !s.contains("?") && VIDEO_ID.matcher(s).matches()) {
            return s;
        }
        int q = s.indexOf("?v=");
        if (q < 0) q = s.indexOf("&v=");
        if (q >= 0) {
            String tail = s.substring(q + 3);
            int amp = indexOfAny(tail, '&', '#');
            String candidate = amp >= 0 ? tail.substring(0, amp) : tail;
            if (VIDEO_ID.matcher(candidate).matches()) return candidate;
        }
        Matcher pm = URL_PATH_ID.matcher(s);
        if (pm.find()) return pm.group(1);
        int sb = s.indexOf("youtu.be/");
        if (sb >= 0) {
            String tail = s.substring(sb + "youtu.be/".length());
            int end = indexOfAny(tail, '?', '&', '#', '/');
            String candidate = end >= 0 ? tail.substring(0, end) : tail;
            if (VIDEO_ID.matcher(candidate).matches()) return candidate;
        }
        return null;
    }

    private static int indexOfAny(String s, char... chars) {
        int best = -1;
        for (char c : chars) {
            int i = s.indexOf(c);
            if (i >= 0 && (best < 0 || i < best)) best = i;
        }
        return best;
    }

    static List<String> parseLanguages(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_LANGUAGES;
        List<String> out = new ArrayList<>();
        for (String token : raw.split("[,\\s]+")) {
            String norm = token.trim().toLowerCase(Locale.ROOT);
            if (!norm.isEmpty()) out.add(norm);
        }
        return out.isEmpty() ? DEFAULT_LANGUAGES : List.copyOf(out);
    }

    static String formatPlain(TranscriptContent content) {
        StringBuilder sb = new StringBuilder();
        for (TranscriptContent.Fragment f : content.getContent()) {
            String t = normaliseText(f.getText());
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(t);
        }
        return sb.toString();
    }

    static String formatWithTimestamps(TranscriptContent content) {
        StringBuilder sb = new StringBuilder();
        for (TranscriptContent.Fragment f : content.getContent()) {
            String t = normaliseText(f.getText());
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append('[').append(formatHhmmss(f.getStart())).append("] ");
            sb.append(t);
        }
        return sb.toString();
    }

    static String formatAsrPlain(WhisperTranscriber.Result result) {
        StringBuilder sb = new StringBuilder();
        for (WhisperTranscriber.Fragment f : result.segments()) {
            String t = f.text().trim();
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(t);
        }
        return sb.toString();
    }

    static String formatAsrWithTimestamps(WhisperTranscriber.Result result) {
        StringBuilder sb = new StringBuilder();
        for (WhisperTranscriber.Fragment f : result.segments()) {
            String t = f.text().trim();
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append('[').append(formatHhmmss(f.start())).append("] ").append(t);
        }
        return sb.toString();
    }

    private static String normaliseText(@Nullable String raw) {
        if (raw == null) return "";
        return raw
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replaceAll("\\s+", " ")
                .trim();
    }

    static String formatHhmmss(double seconds) {
        long total = Math.max(0, (long) Math.floor(seconds));
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", h, m, s);
    }

    static double computeDurationSec(TranscriptContent content) {
        double last = 0;
        for (TranscriptContent.Fragment f : content.getContent()) {
            double end = f.getStart() + f.getDur();
            if (end > last) last = end;
        }
        return Math.round(last * 100.0) / 100.0;
    }

    private static @Nullable String asString(@Nullable Object v) {
        return v == null ? null : v.toString();
    }

    private static boolean asBoolean(@Nullable Object v, boolean fallback) {
        if (v == null) return fallback;
        if (v instanceof Boolean b) return b;
        String s = v.toString().trim().toLowerCase(Locale.ROOT);
        if (s.equals("true") || s.equals("1") || s.equals("yes")) return true;
        if (s.equals("false") || s.equals("0") || s.equals("no")) return false;
        return fallback;
    }

    /** Marker exception — captions stage failed, ASR may take over. */
    private static class NoCaptionsException extends RuntimeException {
        NoCaptionsException(String msg, Throwable cause) { super(msg, cause); }
    }

    /** {@code fallback} parameter values. */
    enum FallbackMode {
        /** Captions first, ASR if missing. Default. */
        AUTO,
        /** Captions only — error out if none. */
        CAPTIONS_ONLY,
        /** Skip captions entirely, go straight to ASR. */
        ASR_ONLY;

        static FallbackMode parse(@Nullable String s) {
            if (s == null || s.isBlank()) return AUTO;
            return switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "captions" -> CAPTIONS_ONLY;
                case "asr" -> ASR_ONLY;
                case "auto", "default" -> AUTO;
                default -> AUTO;
            };
        }
    }
}
