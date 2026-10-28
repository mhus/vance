package de.mhus.vance.exec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Downloads the audio track of a YouTube video via the {@code yt-dlp}
 * binary on the host PATH. Output is an mp3 in the system temp dir
 * named {@code yt-<videoId>.mp3}; the caller is responsible for
 * deleting it after transcription.
 *
 * <p>Pure Java around the program: the brain and the runner run the same code. Requires
 * {@code yt-dlp} and {@code ffmpeg} to be installed:
 * <ul>
 *   <li>macOS: {@code brew install yt-dlp ffmpeg}</li>
 *   <li>Debian/Ubuntu container: {@code apt-get install -y yt-dlp ffmpeg}
 *       (the Dockerfile in {@code deployment/docker/brain/} does this).</li>
 * </ul>
 *
 * <p>Network access is required at runtime — yt-dlp talks to YouTube
 * directly. YouTube blocks many cloud-provider IPs; on a blocked
 * host you'll see a 403 / "Sign in to confirm you're not a bot"
 * error surfacing as a {@link MechException}.
 */
@Slf4j
public final class YtDlpEngine {

    /** Sealed child environment — yt-dlp sees none of the host's credentials. */
    private final ProcessLauncher launcher;

    public YtDlpEngine(ProcessLauncher launcher) {
        this.launcher = launcher;
    }

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(15);

    /** Probed once on first use — the image does not change under a running pod. */
    private volatile @Nullable Boolean available;

    /** Whether yt-dlp runs here; the cloud image ships without it. */
    public boolean available() {
        Boolean known = available;
        if (known == null) {
            known = ProgramProbe.runs(launcher, List.of("yt-dlp", "--version"), PROBE_TIMEOUT);
            available = known;
            if (!known) {
                log.info("yt-dlp unavailable on this host");
            }
        }
        return known;
    }

    /**
     * Wall-clock cap for the download. Audio extraction is normally
     * sub-minute even for hour-long videos; anything beyond this is
     * almost certainly a stalled connection or rate-limited host.
     */
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(5);

    /**
     * Pulls the audio of {@code videoId} as a mono mp3 into
     * {@code outDir} — the caller's scratch directory, which it removes
     * afterwards with everything yt-dlp left there. Returns the path to
     * the freshly created file.
     *
     * @param videoId an 11-char YouTube video id
     * @param outDir  an existing, empty directory the child may write
     * @return the local mp3 path
     * @throws MechException if yt-dlp is missing, returns non-zero, or
     *                       times out
     */
    public Path download(String videoId, Path outDir) {
        // An isolated yt-dlp sees only this directory, and its output —
        // which does not exist before the run — lands inside.
        Path outFile = outDir.resolve("yt-" + videoId + ".mp3");

        // -x: extract audio, --audio-format mp3 picks the encoder.
        // --audio-quality 5: VBR ~64-96 kbps — plenty for ASR, fast
        // download. --no-playlist: do not follow playlist params on
        // the URL. -o: explicit output path so we know where the
        // file lands without scraping yt-dlp's stdout.
        List<String> cmd = List.of(
                "yt-dlp",
                "-x",
                "--audio-format",
                "mp3",
                "--audio-quality",
                "5",
                "--no-playlist",
                "--no-warnings",
                "--quiet",
                "-o",
                outFile.toString(),
                "https://www.youtube.com/watch?v=" + videoId);

        log.info("yt-dlp download videoId='{}' target='{}'", videoId, outFile);
        long startMs = System.currentTimeMillis();
        Process process;
        try {
            process = launcher.start(ProcessSpec.of(cmd, outDir).withNetwork().withWritable(List.of(outDir)));
        } catch (IOException e) {
            throw new MechException(
                    MechException.Reason.PROCESSING_ERROR,
                    "Failed to start yt-dlp — is it installed and on "
                            + "the host PATH? (macOS: brew install yt-dlp; "
                            + "container: apt-get install yt-dlp). "
                            + "Underlying error: " + e.getMessage(),
                    e);
        }

        String stderr;
        try {
            stderr = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(DOWNLOAD_TIMEOUT.toMinutes(), TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new MechException(
                        MechException.Reason.PROCESSING_ERROR,
                        "yt-dlp timed out after " + DOWNLOAD_TIMEOUT.toMinutes() + " minutes for video " + videoId);
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new MechException(
                    MechException.Reason.PROCESSING_ERROR, "Interrupted while downloading audio for " + videoId, e);
        } catch (IOException e) {
            throw new MechException(
                    MechException.Reason.PROCESSING_ERROR, "yt-dlp output stream failed: " + e.getMessage(), e);
        }

        int exit = process.exitValue();
        long elapsedMs = System.currentTimeMillis() - startMs;
        if (exit != 0) {
            throw new MechException(
                    MechException.Reason.PROCESSING_ERROR,
                    "yt-dlp failed (exit " + exit + ") for video " + videoId + ": " + lastLine(stderr));
        }

        if (!Files.isRegularFile(outFile)) {
            throw new MechException(
                    MechException.Reason.PROCESSING_ERROR,
                    "yt-dlp claimed success but output file is missing: " + outFile);
        }

        long sizeBytes;
        try {
            sizeBytes = Files.size(outFile);
        } catch (IOException e) {
            sizeBytes = -1;
        }
        log.info("yt-dlp download videoId='{}' done elapsedMs={} bytes={}", videoId, elapsedMs, sizeBytes);
        return outFile;
    }

    /**
     * yt-dlp's last non-blank stderr line is usually the most relevant
     * for diagnosis (e.g. "ERROR: [youtube] Sign in to confirm…").
     * Truncated to keep the tool result terse.
     */
    private static String lastLine(String stderr) {
        if (stderr == null) return "(no output)";
        String[] lines = stderr.trim().split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String l = lines[i].trim();
            if (!l.isEmpty()) {
                return l.length() > 300 ? l.substring(0, 300) + "…" : l;
            }
        }
        return "(no output)";
    }
}
