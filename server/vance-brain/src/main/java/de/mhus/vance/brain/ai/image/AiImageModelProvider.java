package de.mhus.vance.brain.ai.image;

import de.mhus.vance.brain.ai.ProviderType;
import de.mhus.vance.shared.document.ImageDestinationStream;

/**
 * A provider plug-in for {@link AiImageService}. One Spring bean per
 * vendor backend (OpenAI, Gemini, ...). {@link AiImageService}
 * auto-discovers all beans of this type and indexes them by
 * {@link #getType()}.
 *
 * <p>Providers are stateless w.r.t. a given call — each
 * {@link #generate(AiImageConfig, String, ImageDestinationStream)}
 * invocation issues a fresh HTTP request against the vendor API. Any
 * per-provider caching / rate limiting stays inside the implementation.
 *
 * <p>The output contract is the {@link ImageDestinationStream}: the
 * provider writes bytes through its {@code OutputStream} surface, sets
 * the mime type, and attaches reproducibility metadata (revised
 * prompt, seed, model id) through the typed setters. The provider does
 * not know how the bytes are persisted — that's the destination's
 * concern.
 */
public interface AiImageModelProvider {

    /**
     * Typed identity of the backend this provider speaks to. Drives the
     * dispatch map in {@link AiImageService}.
     */
    ProviderType getType();

    /**
     * Registered provider name, lowercase. Defaults to
     * {@link ProviderType#wireName()} so new providers don't have to
     * duplicate the constant.
     */
    default String getName() {
        return getType().wireName();
    }

    /**
     * Generate one image from {@code prompt} using {@code config} and
     * stream the bytes + metadata into {@code destination}. The
     * provider is responsible for calling {@link ImageDestinationStream#close()}
     * exactly once, after all bytes and metadata are set — that
     * commits the result.
     *
     * @throws AiImageException on provider error, timeout, or
     *                         decoding failure
     */
    void generate(AiImageConfig config, String prompt,
                  ImageDestinationStream destination);
}
