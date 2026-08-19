package de.mhus.vance.toolpack.feed;

import java.util.Map;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Configuration handed by {@code FeedSourceFactory} to
 * {@link FeedProtocol#instantiate} when it builds one instance from the
 * {@code centauri.endpoint.<id>.*} settings.
 *
 * <p>{@code credentials} is a supplier rather than a value, and that shape is
 * doing three jobs at once:
 * <ul>
 *   <li><b>Read on demand.</b> A rotated credential takes effect without
 *       waiting for the factory cache to expire.
 *   <li><b>No scope threading.</b> The instance is already built per
 *       {@code (tenant, project)}, so the supplier closes over that scope.
 *       Handing the scope to a protocol instead would hand it a user id, and
 *       the whole point of deriving {@link FeedActor} centrally is that no
 *       protocol implementation ever sees one.
 *   <li><b>No secret in a record.</b> A credential field would land in the
 *       auto-generated {@code toString()} of every request record it travelled
 *       in, which is one debug log away from being leaked.
 * </ul>
 * It may return null — an unauthenticated source is the normal case.
 *
 * <p>{@code credentialSettingKey} stays for diagnostics: it names where the
 * value would come from, which is what an operator needs when it is missing.
 *
 * <p>{@code extras} carries protocol-specific knobs outside the common
 * fields. Protocols take what they know; unknown keys are ignored.
 */
public record FeedInstanceConfig(
        String instanceId,
        String protocolId,
        String baseUrl,
        String credentialSettingKey,
        Supplier<@Nullable String> credentials,
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
        if (credentialSettingKey == null) {
            credentialSettingKey = "";
        }
        if (credentials == null) {
            credentials = () -> null;
        }
        extras = extras == null ? Map.of() : Map.copyOf(extras);
    }

    /** The credential right now, or null when this source is unauthenticated. */
    public @Nullable String credential() {
        return credentials.get();
    }

    /** A protocol-specific knob as text, or {@code fallback} when unset. */
    public String extra(String key, String fallback) {
        Object value = extras.get(key);
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }
}
