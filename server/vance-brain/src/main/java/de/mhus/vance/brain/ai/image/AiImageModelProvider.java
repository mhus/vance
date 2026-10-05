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
     * Optional per-instance dispatch key. Present when this adapter
     * serves ONE named provider instance whose image wire differs from
     * the chat wire the instance declares — the OpenRouter shape
     * (chat: {@code wireType: openai}, images: dedicated
     * {@code POST /api/v1/images}). When present, {@link AiImageService}
     * dispatches on the instance label of an incoming
     * {@link AiImageConfig} before falling back to the protocol type.
     */
    default java.util.Optional<String> getInstanceName() {
        return java.util.Optional.empty();
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
    void generate(AiImageConfig config, String prompt, ImageDestinationStream destination);

    /**
     * Generate one image from {@code prompt} <b>plus reference
     * images</b> (image-to-image editing, style transfer, variations)
     * and stream the result into {@code destination}.
     *
     * <p>References arrive already materialised: resolved from
     * documents by the caller (Fenchurch) with mime type and bytes —
     * providers do no document I/O. Ordering is preserved: the first
     * reference is the primary subject.
     *
     * <p>Default implementation fails closed: a provider that does not
     * override this does not support editing, and the catalog's
     * {@code maxInputReferences} gate should have rejected the call
     * before it ever reached the adapter. The override lives with the
     * adapter because the wire shapes are genuinely different
     * (OpenRouter: {@code input_references}; OpenAI: multipart
     * {@code /images/edits}; Gemini: multimodal edit) — one contract,
     * three transports.
     *
     * <p>Same destination contract as {@link #generate}: set mime +
     * metadata, write bytes, {@link ImageDestinationStream#close()}
     * exactly once on success — never on failure.
     *
     * @throws AiImageException on provider error, timeout, decoding
     *                         failure, or when this adapter cannot edit
     */
    default void edit(
            AiImageConfig config,
            String prompt,
            java.util.List<ImageReference> references,
            ImageDestinationStream destination) {
        throw new AiImageException("Image editing is not supported by the " + getName() + " adapter");
    }
}
