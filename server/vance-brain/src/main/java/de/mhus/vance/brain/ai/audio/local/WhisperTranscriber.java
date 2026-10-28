package de.mhus.vance.brain.ai.audio.local;

import de.mhus.vance.exec.MechException;
import de.mhus.vance.exec.ProcessLauncher;
import de.mhus.vance.exec.WhisperEngine;
import de.mhus.vance.toolpack.ToolException;
import java.io.IOException;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Local transcription with {@code faster-whisper} on this pod: {@link WhisperEngine}'s work —
 * the same code a runner uses — behind the sealed child environment of the pod's launcher, with
 * failures as {@link ToolException}s.
 *
 * <p>The cloud image ships without it (whisper belongs to the project's runner there):
 * {@link #available()} is then {@code false} and callers report a clear "not available on this
 * server" instead of a failing start.
 */
@Component
public class WhisperTranscriber {

    private final WhisperEngine engine;

    public WhisperTranscriber(
            @Value("${vance.transcription.python:python3}") String pythonExecutable, ProcessLauncher launcher)
            throws IOException {
        this.engine = new WhisperEngine(pythonExecutable, launcher);
    }

    /** Whether local transcription can run here: Python with faster-whisper installed. */
    public boolean available() {
        return engine.available();
    }

    /**
     * Transcribes an audio file.
     *
     * @param model      whisper model name; falls back to {@code "small"} when blank
     * @param language   language code (e.g. {@code "de"}) or {@code null} for auto-detect
     * @param onProgress optional progress sink — one call per fragment
     * @throws ToolException on subprocess failure, timeout, non-zero exit or unparseable output
     */
    public WhisperEngine.Result transcribe(
            Path audioPath,
            @Nullable String model,
            @Nullable String language,
            WhisperEngine.@Nullable ProgressSink onProgress) {
        try {
            return engine.transcribe(audioPath, model, language, onProgress);
        } catch (MechException e) {
            throw new ToolException(e.getMessage(), e);
        }
    }
}
