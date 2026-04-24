package de.mhus.vance.brain.ai;

/**
 * Resolved configuration for an {@link AiChat}: which provider, which model,
 * and the credential to authenticate with. All lookups (which model is
 * "default" for a scope, where the API key lives as a setting) happen
 * <b>before</b> this record is built — {@link AiModelService} takes the
 * record as-is and instantiates the chat.
 *
 * @param provider provider name registered in {@link AiModelService} (e.g.
 *                 {@code "anthropic"})
 * @param modelName provider-specific model identifier (e.g.
 *                 {@code "claude-sonnet-4-5"})
 * @param apiKey  provider credential (plaintext — the caller already
 *                 decrypted it via {@code SettingService.getDecryptedPassword})
 */
public record AiChatConfig(String provider, String modelName, String apiKey) {

    public AiChatConfig {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider is blank");
        }
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("modelName is blank");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey is blank");
        }
    }

    /** {@code "provider:modelName"} form, used for logs and the AiChat name. */
    public String fullName() {
        return provider + ":" + modelName;
    }
}
