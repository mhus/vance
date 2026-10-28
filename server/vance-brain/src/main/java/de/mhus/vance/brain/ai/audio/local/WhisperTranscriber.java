package de.mhus.vance.brain.ai.audio.local;

import de.mhus.vance.shared.process.ProcessLauncher;
import de.mhus.vance.shared.process.ProcessSpec;
import de.mhus.vance.toolpack.ToolException;
import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the bundled {@code transcribe.py} wrapper that invokes
 * {@code faster-whisper} on an audio file. The wrapper is extracted
 * once at startup into the system temp dir; subsequent calls reuse
 * the same path.
 *
 * <p>Requires a Python 3 interpreter on the host PATH with
 * {@code faster-whisper} installed:
 * <ul>
 *   <li>macOS: {@code pip3 install --user faster-whisper}</li>
 *   <li>Container: {@code pip install faster-whisper} (the
 *       Dockerfile in {@code deployment/docker/brain/} does this).</li>
 * </ul>
 *
 * <p>The Python wrapper streams progress lines to stderr while it
 * processes; the {@link #transcribe} method forwards each
 * {@code PROGRESS chunkEndSec=… durationSec=…} line to an optional
 * consumer so the caller can ping the user-progress channel.
 */
@Component
@Slf4j
public class WhisperTranscriber {

    /**
     * Wall-clock cap for one transcription. Even a 60-min video on
     * CPU-only Whisper-small finishes in ~15-30 min; the cap is set
     * generously so legitimate slow runs survive while truly stuck
     * processes are killed.
     */
    private static final Duration TRANSCRIBE_TIMEOUT = Duration.ofHours(1);

    /** Default model when the caller doesn't specify. */
    private static final String DEFAULT_MODEL = "small";

    @Value("${vance.transcription.python:python3}")
    private String pythonExecutable;

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    /** Sealed child environment — the wrapper sees none of the brain's credentials. */
    private final ProcessLauncher launcher;

    /**
     * Model-cache locations passed through to the child: the image points them
     * at {@code /app/.cache} so models survive between runs. Not secrets — and
     * without them the sealed environment would re-download every model.
     */
    private static final List<String> CACHE_VARIABLES = List.of("HF_HOME", "XDG_CACHE_HOME");

    public WhisperTranscriber(ProcessLauncher launcher) {
        this.launcher = launcher;
    }

    private Path scriptPath;

    @PostConstruct
    void extractScript() throws IOException {
        Path target = Path.of(System.getProperty("java.io.tmpdir"), "vance-whisper-transcribe.py");
        try (InputStream in = new ClassPathResource("scripts/transcribe.py").getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        scriptPath = target;
        log.info("WhisperTranscriber: wrapper script extracted to {}", scriptPath);
    }

    /**
     * Transcribe an audio file. Returns the parsed JSON result as a
     * {@link Result} record.
     *
     * @param audioPath path to the audio file (mp3/wav/m4a/…)
     * @param model     whisper model name; falls back to {@code "small"}
     *                  when blank
     * @param language  language code (e.g. {@code "de"}) or {@code null}
     *                  for auto-detect
     * @param onProgress optional progress sink — receives one call per
     *                  fragment with the current and total seconds
     * @return parsed transcription result
     * @throws ToolException on subprocess failure / timeout / non-zero
     *                       exit / unparseable output
     */
    public Result transcribe(
            Path audioPath, @Nullable String model, @Nullable String language, @Nullable ProgressSink onProgress) {
        if (scriptPath == null) {
            throw new ToolException("Whisper wrapper not initialised");
        }
        String effectiveModel = (model == null || model.isBlank()) ? DEFAULT_MODEL : model;
        String effectiveLang = (language == null || language.isBlank()) ? "auto" : language;

        List<String> cmd =
                List.of(pythonExecutable, scriptPath.toString(), audioPath.toString(), effectiveModel, effectiveLang);
        log.info("Whisper transcribe model='{}' lang='{}' audio='{}'", effectiveModel, effectiveLang, audioPath);

        long startMs = System.currentTimeMillis();
        Process process;
        try {
            // Network for the first model download; the model cache is the
            // only place it writes.
            Map<String, String> cache = cacheEnvironment();
            process = launcher.start(ProcessSpec.of(cmd, null)
                    .withSeparateErrorStream()
                    .withEnvironment(cache)
                    .withNetwork()
                    .withReadable(List.of(scriptPath, audioPath))
                    .withWritable(cacheDirectories(cache.values())));
        } catch (IOException e) {
            throw new ToolException(
                    "Failed to start Python — is '" + pythonExecutable
                            + "' on the host PATH? (macOS: ensure python3 "
                            + "is installed; container: the brain image "
                            + "ships python3). Underlying: "
                            + e.getMessage(),
                    e);
        }

        // Drain stderr in a daemon thread so the progress sink fires
        // live and the buffer never blocks the child.
        List<String> stderrLines = new ArrayList<>();
        Thread stderrThread = new Thread(
                () -> readStderr(process.getErrorStream(), stderrLines, onProgress),
                "whisper-stderr-" + audioPath.getFileName());
        stderrThread.setDaemon(true);
        stderrThread.start();

        StringBuilder stdout = new StringBuilder();
        try (BufferedReader r =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                stdout.append(line).append('\n');
            }
        } catch (IOException e) {
            process.destroyForcibly();
            throw new ToolException("Whisper stdout read failed: " + e.getMessage(), e);
        }

        boolean finished;
        try {
            finished = process.waitFor(TRANSCRIBE_TIMEOUT.toMinutes(), TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new ToolException("Interrupted during transcription", e);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new ToolException("Whisper transcription timed out after "
                    + TRANSCRIBE_TIMEOUT.toMinutes()
                    + " minutes — try a smaller model or a "
                    + "shorter video");
        }

        try {
            stderrThread.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long elapsedMs = System.currentTimeMillis() - startMs;
        int exit = process.exitValue();
        if (exit != 0) {
            throw new ToolException(
                    "Whisper transcription failed (exit " + exit + "): " + lastNonBlankLine(stderrLines));
        }

        String json = stdout.toString().trim();
        if (json.isEmpty()) {
            throw new ToolException("Whisper transcription produced no output");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (RuntimeException e) {
            throw new ToolException("Could not parse Whisper output as JSON: " + e.getMessage(), e);
        }
        if (root.has("error")) {
            throw new ToolException(
                    "Whisper wrapper error: " + root.get("error").asText());
        }
        log.info(
                "Whisper transcribe done elapsedMs={} segments={} " + "language='{}'",
                elapsedMs,
                root.path("segments").size(),
                root.path("language").asText());
        return Result.from(root, elapsedMs);
    }

    private static void readStderr(InputStream stream, List<String> sink, @Nullable ProgressSink onProgress) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sink.add(line);
                if (onProgress != null && line.startsWith("PROGRESS ")) {
                    parseProgress(line, onProgress);
                }
            }
        } catch (IOException e) {
            // The child process exited; nothing actionable.
        }
    }

    /** Parse a {@code PROGRESS chunkEndSec=… durationSec=…} line. */
    private static void parseProgress(String line, ProgressSink sink) {
        double chunkEnd = parseField(line, "chunkEndSec=");
        double duration = parseField(line, "durationSec=");
        if (chunkEnd >= 0) sink.onChunk(chunkEnd, duration);
    }

    private static double parseField(String line, String key) {
        int idx = line.indexOf(key);
        if (idx < 0) return -1;
        int end = line.indexOf(' ', idx + key.length());
        String num = end < 0 ? line.substring(idx + key.length()) : line.substring(idx + key.length(), end);
        try {
            return Double.parseDouble(num);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String lastNonBlankLine(List<String> lines) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            String l = lines.get(i).trim();
            if (!l.isEmpty()) {
                return l.length() > 300 ? l.substring(0, 300) + "…" : l;
            }
        }
        return "(no output)";
    }

    @FunctionalInterface
    public interface ProgressSink {
        /**
         * Called once per fragment as faster-whisper streams its
         * results. {@code chunkEndSec} is the timestamp inside the
         * audio that's been transcribed so far; {@code durationSec}
         * is the total duration of the audio (or 0 if unknown).
         */
        void onChunk(double chunkEndSec, double durationSec);
    }

    /**
     * Parsed result. Fragments mirror the on-the-wire shape used by
     * {@code TranscriptContent.Fragment} for consistency with the
     * captions path.
     */
    public record Result(
            String language,
            double languageProbability,
            double durationSec,
            double elapsedSec,
            String modelName,
            List<Fragment> segments) {

        static Result from(JsonNode root, long elapsedMs) {
            List<Fragment> segs = new ArrayList<>();
            for (JsonNode n : root.path("segments")) {
                segs.add(new Fragment(
                        n.path("start").asDouble(0),
                        n.path("end").asDouble(0),
                        n.path("text").asString("")));
            }
            return new Result(
                    root.path("language").asString(""),
                    root.path("languageProbability").asDouble(0),
                    root.path("durationSec").asDouble(0),
                    root.path("elapsedSec").asDouble(elapsedMs / 1000.0),
                    root.path("modelName").asString(""),
                    segs);
        }
    }

    public record Fragment(double start, double end, String text) {}

    /**
     * The configured cache directories, created when missing — an isolated child
     * can only write into a directory that exists before it starts.
     */
    private static List<Path> cacheDirectories(Collection<String> paths) {
        List<Path> out = new ArrayList<>();
        for (String path : paths) {
            Path dir = Path.of(path);
            try {
                Files.createDirectories(dir);
                out.add(dir);
            } catch (IOException e) {
                log.warn("Whisper cache directory {} is not usable: {}", dir, e.getMessage());
            }
        }
        return out;
    }

    private static Map<String, String> cacheEnvironment() {
        Map<String, String> env = new LinkedHashMap<>();
        for (String name : CACHE_VARIABLES) {
            String value = System.getenv(name);
            if (value != null) {
                env.put(name, value);
            }
        }
        return env;
    }
}
