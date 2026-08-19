package de.mhus.vance.toolpack.feed;

import java.util.Map;

/**
 * Configuration handed by {@code FeedSourceFactory} to
 * {@link FeedProtocol#instantiate} when it builds one instance from the
 * {@code centauri.endpoint.<id>.*} settings.
 *
 * <p>{@code credentialSettingKey} is the key the instance looks up at
 * request time — read on demand so a rotated credential is picked up
 * without re-assembling the factory cache. The factory never reads the
 * credential itself.
 *
 * <p>{@code extras} carries protocol-specific knobs outside the three
 * common fields. Protocols take what they know; unknown keys are ignored.
 */
public record FeedInstanceConfig(
        String instanceId,
        String protocolId,
        String baseUrl,
        String credentialSettingKey,
        Map<String, Object> extras) {

    public FeedInstanceConfig {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId is required");
        }
        if (protocolId == null || protocolId.isBlank()) {
            throw new IllegalArgumentException("protocolId is required");
        }
        if (baseUrl == null) {
            baseUrl = "";
        }
        extras = extras == null ? Map.of() : Map.copyOf(extras);
    }
}
