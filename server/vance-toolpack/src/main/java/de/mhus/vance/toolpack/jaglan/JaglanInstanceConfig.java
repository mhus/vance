package de.mhus.vance.toolpack.jaglan;

import java.util.Map;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

/**
 * One configured mount, as assembled from {@code jaglan.mount.<name>.*}
 * settings and handed to {@link JaglanProtocol#instantiate}.
 *
 * <p>Two rules hang off this record:
 *
 * <ul>
 *   <li><b>No secret in a record.</b> A credential field would land in the
 *       auto-generated {@code toString()} of every record it travels
 *       through and from there into a log line. Hence a
 *       {@link Supplier}, resolved at the moment of use.</li>
 *   <li><b>Project scope, carried explicitly.</b> Mounts are configured per
 *       project and never cascade to {@code _tenant}, so both ids travel
 *       with the config — a protocol that needs to scope a remote call has
 *       them without reaching for a request context.</li>
 * </ul>
 *
 * @param mount                the mount name, already validated against the
 *                             path-segment grammar by the factory
 * @param protocolId           which protocol serves it
 * @param baseUrl              endpoint, empty for protocols that need none
 * @param credentialSettingKey the setting the credential came from, for
 *                             diagnostics — never the value
 * @param credentials          resolved on demand, {@code null} when unset
 * @param extras               protocol-specific knobs from the settings
 */
public record JaglanInstanceConfig(
        String mount,
        String protocolId,
        String baseUrl,
        String credentialSettingKey,
        Supplier<@Nullable String> credentials,
        String tenantId,
        String projectId,
        Map<String, Object> extras) {

    public JaglanInstanceConfig {
        if (mount == null || mount.isBlank()) {
            throw new IllegalArgumentException("mount is required");
        }
        if (protocolId == null || protocolId.isBlank()) {
            throw new IllegalArgumentException("protocolId is required");
        }
        if (baseUrl == null) baseUrl = "";
        if (credentialSettingKey == null) credentialSettingKey = "";
        if (credentials == null) credentials = () -> null;
        if (tenantId == null) tenantId = "";
        if (projectId == null) projectId = "";
        extras = extras == null ? Map.of() : Map.copyOf(extras);
    }

    /** An extras value as string, or {@code null} when absent or blank. */
    public @Nullable String extraString(String key) {
        Object value = extras.get(key);
        if (value == null) return null;
        String text = String.valueOf(value).strip();
        return text.isEmpty() ? null : text;
    }
}
