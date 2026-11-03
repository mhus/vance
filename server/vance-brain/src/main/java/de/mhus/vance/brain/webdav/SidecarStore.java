package de.mhus.vance.brain.webdav;

import de.mhus.vance.shared.redis.VanceRedisMessagingService;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * Opaque TTL cache for macOS/Windows client-noise sidecar files
 * ({@code .DS_Store}, {@code ._*}, …).
 *
 * <p>These are never persisted as real documents — they carry Finder window
 * state and AppleDouble extended attributes, not user content. Storing them
 * verbatim (no parsing) in the messaging service's TTL values lets Finder keep its folder-view
 * fidelity across requests and pods without polluting the document store. The
 * blobs are cosmetic: on expiry the folder view merely resets. See
 * {@code planning/webdav-support.md} §6.
 *
 * <p>Values are Base64-encoded because the messaging service stores strings; sidecars are small (a few KB) so the overhead is
 * negligible.
 */
@Slf4j
public class SidecarStore {

    private final VanceRedisMessagingService messaging;
    private final WebDavProperties properties;

    public SidecarStore(VanceRedisMessagingService messaging, WebDavProperties properties) {
        this.messaging = messaging;
        this.properties = properties;
    }

    public void put(String tenantId, String projectId, String path, byte[] data) {
        String value = Base64.getEncoder().encodeToString(data);
        messaging.valuePut(tenantId, key(projectId, path), value, properties.getSidecarTtl());
    }

    public Optional<byte[]> get(String tenantId, String projectId, String path) {
        String value = messaging.valueGet(tenantId, key(projectId, path)).orElse(null);
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Base64.getDecoder().decode(value));
        } catch (IllegalArgumentException e) {
            log.debug("webdav sidecar: corrupt base64 for {}/{}/{} — dropping", tenantId, projectId, path);
            return Optional.empty();
        }
    }

    public boolean exists(String tenantId, String projectId, String path) {
        return messaging.valueExists(tenantId, key(projectId, path));
    }

    public void delete(String tenantId, String projectId, String path) {
        messaging.valueDelete(tenantId, key(projectId, path));
    }

    private String key(String projectId, String path) {
        String composite = projectId + "\0" + path;
        String encoded =
                Base64.getUrlEncoder().withoutPadding().encodeToString(composite.getBytes(StandardCharsets.UTF_8));
        return "webdav:sidecar:" + encoded;
    }
}
