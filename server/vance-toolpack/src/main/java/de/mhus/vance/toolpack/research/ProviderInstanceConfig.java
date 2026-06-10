package de.mhus.vance.toolpack.research;

import java.util.Map;

/**
 * Configuration handed by {@code SearchProviderFactory} to
 * {@link SearchProtocol#instantiate} when it builds one instance of a
 * protocol from the {@code research.endpoint.<id>.*} settings.
 *
 * <p>{@code credentialSettingKey} is the setting key the instance will
 * look up at request time via {@code SettingService} — read on demand
 * so a rotated key is picked up without re-assembling the factory
 * cache. The factory itself never reads the credential.
 *
 * <p>{@code extras} carries protocol-specific tuning knobs that don't
 * fit into the four common fields (e.g. {@code regionHint},
 * {@code timeoutMs}, OpenAlex's {@code contactEmail}). Protocols pick
 * what they need; unknown keys are ignored.
 */
public record ProviderInstanceConfig(
        String instanceId,
        String protocolId,
        String baseUrl,
        String credentialSettingKey,
        Map<String, Object> extras) {

    public ProviderInstanceConfig {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId is required");
        }
        if (protocolId == null || protocolId.isBlank()) {
            throw new IllegalArgumentException("protocolId is required");
        }
        extras = extras == null ? Map.of() : Map.copyOf(extras);
    }
}
