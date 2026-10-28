package de.mhus.vance.brain.ai.audio.local;

import de.mhus.vance.brain.ai.ProviderType;
import de.mhus.vance.brain.ai.audio.AiAudioConfig;
import de.mhus.vance.brain.ai.audio.AiAudioException;
import de.mhus.vance.brain.ai.audio.AiAudioModelProvider;
import de.mhus.vance.brain.ai.audio.AudioSource;
import de.mhus.vance.brain.ai.audio.SttResult;
import de.mhus.vance.exec.ScratchArea;
import de.mhus.vance.exec.ScratchArea.Scratch;
import de.mhus.vance.exec.WhisperEngine;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Local speech-to-text via the bundled {@code faster-whisper} wrapper —
 * no network, no API key, unpriced in the ledger. Dispatch hangs off
 * the instance label {@code "local"} (model docs live under
 * {@code _vance/model/local/}).
 *
 * <p>This is the offline fallback of the audio stack. The ASR engine
 * is {@link WhisperTranscriber} — one invocation point shared with the
 * ASR fallback of {@code video_transcript} (that tool keeps calling the
 * engine directly because it formats per-segment timestamps and streams
 * progress, which the slim {@code SttResult} contract deliberately does
 * not carry). The whisper model name rides the model id
 * ({@code faster-whisper-small} / {@code -medium} / {@code -large});
 * the adapter strips the prefix when handing the name to the wrapper.
 *
 * <p>Only {@link #transcribe} is implemented — synthesis and music stay
 * on their default fail-closed.
 */
@Component
@RequiredArgsConstructor
public class LocalWhisperProvider implements AiAudioModelProvider {

    /** Dispatch key — matches the bundled {@code local/_provider.yaml}. */
    static final String INSTANCE_NAME = "local";

    /** Model-id prefix that carries the whisper model size. */
    static final String MODEL_PREFIX = "faster-whisper-";

    private final WhisperTranscriber whisperTranscriber;
    private final ScratchArea scratchArea;

    @Override
    public ProviderType getType() {
        return ProviderType.LOCAL;
    }

    @Override
    public Optional<String> getInstanceName() {
        return Optional.of(INSTANCE_NAME);
    }

    @Override
    public SttResult transcribe(AiAudioConfig config, AudioSource source, @Nullable String language) {
        if (!whisperTranscriber.available()) {
            throw new AiAudioException("Local transcription (Whisper) is not available on this server —"
                    + " choose a provider-hosted transcription model instead of " + config.fullName());
        }
        String whisperModel = config.modelName().startsWith(MODEL_PREFIX)
                ? config.modelName().substring(MODEL_PREFIX.length())
                : config.modelName();
        try (Scratch scratch = scratchArea.open("whisper")) {
            Path audio = scratch.write("audio." + source.format(), source.data());
            WhisperEngine.Result result = whisperTranscriber.transcribe(audio, whisperModel, language, null);
            StringBuilder text = new StringBuilder();
            for (WhisperEngine.Fragment fragment : result.segments()) {
                text.append(fragment.text().trim()).append('\n');
            }
            return new SttResult(
                    text.toString().trim(),
                    result.language().isBlank() ? null : result.language(),
                    result.durationSec() > 0 ? result.durationSec() : null,
                    /* reportedCostUsd */ null);
        } catch (IOException e) {
            throw new AiAudioException("Local whisper failed for " + config.fullName() + ": " + e.getMessage(), e);
        }
    }
}
