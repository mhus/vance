package de.mhus.vance.brain.ai.audio;

import de.mhus.vance.brain.ai.ProviderType;
import de.mhus.vance.shared.document.AudioDestinationStream;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A provider plug-in for {@link AiAudioService}. One Spring bean per
 * vendor backend (OpenRouter, local whisper, …). {@link AiAudioService}
 * auto-discovers all beans of this type and indexes them by
 * {@link #getType()} / {@link #getInstanceName()}.
 *
 * <p>Providers are stateless w.r.t. a given call — each invocation
 * issues a fresh request against the vendor API. The output contract
 * for synthesis and music is the {@link AudioDestinationStream}: the
 * provider writes bytes through its {@code OutputStream} surface, sets
 * the mime type, and attaches reproducibility metadata (model id,
 * voice, language, reported cost) through the typed setters. The
 * provider does not know how the bytes are persisted.
 *
 * <p>One interface, three operations — a provider implements the ones
 * its backend actually offers. The unimplemented ones keep their
 * default, which fails closed with
 * {@link AiAudioException#unsupportedOperation}: the service maps that
 * to {@code invalid_choice} before it ever burns quota.
 */
public interface AiAudioModelProvider {

    /** Typed identity of the backend this provider speaks to. */
    ProviderType getType();

    /** Registered provider name, lowercase. Defaults to the wire name. */
    default String getName() {
        return getType().wireName();
    }

    /**
     * Optional per-instance dispatch key. Present when this adapter
     * serves ONE named provider instance whose audio wire differs from
     * the chat wire the instance declares — the OpenRouter shape (chat:
     * {@code wireType: openai}, audio: dedicated {@code /audio/*}
     * routes). When present, {@link AiAudioService} dispatches on the
     * instance label of an incoming {@link AiAudioConfig} before
     * falling back to the protocol type.
     */
    default Optional<String> getInstanceName() {
        return Optional.empty();
    }

    /**
     * Synthesize speech from {@code request} and stream the bytes +
     * metadata into {@code destination}. The provider is responsible
     * for calling {@link AudioDestinationStream#close()} exactly once,
     * after all bytes and metadata are set — that commits the result.
     *
     * @throws AiAudioException on provider error, timeout, or decoding
     *                         failure
     */
    default void synthesize(AiAudioConfig config, TtsRequest request, AudioDestinationStream destination) {
        throw AiAudioException.unsupportedOperation(getName(), "text-to-speech");
    }

    /**
     * Transcribe {@code source} to text.
     *
     * @throws AiAudioException on provider error, timeout, or decoding
     *                         failure
     */
    /**
     * Transcribe {@code source} to text. {@code language} is an
     * ISO-639-1 hint; {@code null} lets the provider auto-detect.
     *
     * @throws AiAudioException on provider error, timeout, or decoding
     *                         failure
     */
    default SttResult transcribe(AiAudioConfig config, AudioSource source, @Nullable String language) {
        throw AiAudioException.unsupportedOperation(getName(), "transcription");
    }

    /**
     * Generate music / non-speech audio from {@code prompt} and stream
     * the result into {@code destination}. Same destination contract as
     * {@link #synthesize}.
     *
     * @throws AiAudioException on provider error, timeout, or decoding
     *                         failure
     */
    default void generateMusic(
            AiAudioConfig config, String prompt, int durationSeconds, AudioDestinationStream destination) {
        throw AiAudioException.unsupportedOperation(getName(), "music generation");
    }
}
