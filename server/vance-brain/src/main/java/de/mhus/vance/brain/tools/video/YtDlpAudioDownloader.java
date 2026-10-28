package de.mhus.vance.brain.tools.video;

import de.mhus.vance.exec.MechException;
import de.mhus.vance.exec.ProcessLauncher;
import de.mhus.vance.exec.YtDlpEngine;
import de.mhus.vance.toolpack.ToolException;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/**
 * Downloads the audio track of a YouTube video with the {@code yt-dlp} on this pod:
 * {@link YtDlpEngine}'s work — the same code a runner uses — behind the pod's sealed child
 * environment, with failures as {@link ToolException}s. The cloud image ships without it;
 * {@link #available()} is then {@code false}.
 */
@Component
public class YtDlpAudioDownloader {

    private final YtDlpEngine engine;

    public YtDlpAudioDownloader(ProcessLauncher launcher) {
        this.engine = new YtDlpEngine(launcher);
    }

    /** Whether yt-dlp runs here. */
    public boolean available() {
        return engine.available();
    }

    /**
     * Pulls the audio of {@code videoId} as a mono mp3 into {@code outDir} — the caller's scratch
     * directory, which it removes afterwards — and returns the path of the new file.
     *
     * @throws ToolException if yt-dlp is missing, fails or times out
     */
    public Path download(String videoId, Path outDir) {
        try {
            return engine.download(videoId, outDir);
        } catch (MechException e) {
            throw new ToolException(e.getMessage(), e);
        }
    }
}
