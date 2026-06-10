package de.mhus.vance.brain.ai.image;

import de.mhus.vance.brain.ai.ProviderType;
import org.jspecify.annotations.Nullable;

/**
 * Resolved configuration for one image-generation call. Mirrors the
 * shape of {@code AiChatConfig} for chats: all lookups (which model is
 * "default:image" for the scope, where the API key lives) happen
 * <b>before</b> this record is built — {@link AiImageService} takes it
 * as-is and dispatches to the matching provider.
 *
 * @param provider         protocol wire-name registered as a
 *                         {@link ProviderType} (e.g. {@code "openai"},
 *                         {@code "gemini"})
 * @param providerInstance instance label used for the
 *                         {@code ai.provider.<instance>.apiKey} /
 *                         {@code .baseUrl} setting lookup. Equals
 *                         {@code provider} for the default instance.
 * @param modelName        provider-specific model identifier
 *                         (e.g. {@code "gpt-image-1"},
 *                         {@code "gemini-2.5-flash-image"})
 * @param apiKey           plaintext provider credential (the caller
 *                         already decrypted it through
 *                         {@code SettingService.getDecryptedPassword})
 * @param baseUrl          optional per-tenant/-project base-URL override.
 *                         {@code null} means "use the provider's
 *                         boot-time default"
 * @param aspectRatio      target aspect ratio (e.g. {@code "1:1"},
 *                         {@code "16:9"}). Provider validates against
 *                         the model's {@code supportedAspectRatios}
 *                         list and converts to its native size/aspect
 *                         parameter.
 * @param timeoutSeconds   per-call HTTP timeout. The provider must
 *                         apply this to its HTTP client so a slow
 *                         model doesn't block the caller longer than
 *                         the Fenchurch service expects.
 */
public record AiImageConfig(
        String provider,
        String providerInstance,
        String modelName,
        String apiKey,
        @Nullable String baseUrl,
        String aspectRatio,
        int timeoutSeconds) {

    public AiImageConfig {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider is blank");
        }
        if (providerInstance == null || providerInstance.isBlank()) {
            throw new IllegalArgumentException("providerInstance is blank");
        }
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("modelName is blank");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey is blank");
        }
        if (aspectRatio == null || aspectRatio.isBlank()) {
            throw new IllegalArgumentException("aspectRatio is blank");
        }
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException(
                    "timeoutSeconds must be > 0, got " + timeoutSeconds);
        }
        if (baseUrl != null && baseUrl.isBlank()) {
            baseUrl = null;
        }
    }

    /** {@code "instance:modelName"} form — what the user wrote in the model spec. */
    public String fullName() {
        return providerInstance + ":" + modelName;
    }

    /** Typed view of {@link #provider()}. */
    public ProviderType providerType() {
        return ProviderType.requireWireName(provider);
    }
}
