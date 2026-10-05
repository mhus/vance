package de.mhus.vance.brain.ai.audio.local;

import de.mhus.vance.brain.ai.ProviderType;
import de.mhus.vance.brain.ai.audio.AiAudioConfig;
import de.mhus.vance.brain.ai.audio.AiAudioException;
import de.mhus.vance.brain.ai.audio.AiAudioModelProvider;
import de.mhus.vance.brain.ai.audio.AudioSource;
import de.mhus.vance.brain.ai.audio.SttResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@RequiredArgsConstructor
public class LocalWhisperProvider implements AiAudioModelProvider {

    /** Dispatch key — matches the bundled {@code local/_provider.yaml}. */
    static final String INSTANCE_NAME = "local";

    /** Model-id prefix that carries the whisper model size. */
    static final String MODEL_PREFIX = "faster-whisper-";

    private final WhisperTranscriber whisperTranscriber;

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
        String whisperModel = config.modelName().startsWith(MODEL_PREFIX)
                ? config.modelName().substring(MODEL_PREFIX.length())
                : config.modelName();
        Path tempFile = null;
        try {
            String suffix = "." + source.format();
            tempFile = Files.createTempFile("vance-stt-", suffix);
            Files.write(tempFile, source.data());
            WhisperTranscriber.Result result = whisperTranscriber.transcribe(tempFile, whisperModel, language, null);
            StringBuilder text = new StringBuilder();
            for (WhisperTranscriber.Fragment fragment : result.segments()) {
                text.append(fragment.text().trim()).append('\n');
            }
            return new SttResult(
                    text.toString().trim(),
                    result.language().isBlank() ? null : result.language(),
                    result.durationSec() > 0 ? result.durationSec() : null,
                    /* reportedCostUsd */ null);
        } catch (IOException e) {
            throw new AiAudioException("Local whisper failed for " + config.fullName() + ": " + e.getMessage(), e);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    log.debug("LocalWhisperProvider: could not delete temp file {}: {}", tempFile, e.toString());
                }
            }
        }
    }
}
