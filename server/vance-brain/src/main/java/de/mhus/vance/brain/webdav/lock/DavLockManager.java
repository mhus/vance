package de.mhus.vance.brain.webdav.lock;

import de.mhus.vance.brain.webdav.AbstractDavResource;
import de.mhus.vance.brain.webdav.WebDavProperties;
import de.mhus.vance.shared.redis.VanceRedisMessagingService;
import io.milton.http.LockInfo;
import io.milton.http.LockManager;
import io.milton.http.LockResult;
import io.milton.http.LockTimeout;
import io.milton.http.LockToken;
import io.milton.resource.LockableResource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * WebDAV lock manager on the messaging service's TTL values, so lock state is visible to every
 * node the service spans (a client's LOCK, PUT and UNLOCK may each land on a different pod): the
 * Redis-backed service in the cloud edition, the in-process one on a single node.
 *
 * <p>Locks are advisory and transient: exclusivity is enforced by a put-if-absent with a TTL
 * (which also reaps crashed clients), while real data integrity
 * comes from the document store's {@code @Version} optimistic lock, not from
 * this. Keyed on the resource's {@code (tenant, project, path)} — stable across
 * content writes (unlike {@code storageId}).
 */
@Slf4j
public class DavLockManager implements LockManager {

    private static final char SEP = '\n';

    private final VanceRedisMessagingService messaging;
    private final WebDavProperties properties;

    public DavLockManager(VanceRedisMessagingService messaging, WebDavProperties properties) {
        this.messaging = messaging;
        this.properties = properties;
    }

    @Override
    public LockResult lock(LockTimeout timeout, LockInfo lockInfo, LockableResource resource) {
        Slot slot = slot(resource);
        long seconds = resolveSeconds(timeout);
        String tokenId = UUID.randomUUID().toString();
        String user = lockInfo != null && lockInfo.lockedByUser != null ? lockInfo.lockedByUser : "";
        boolean ok = messaging.valuePutIfAbsent(
                slot.tenant(), slot.subKey(), encode(tokenId, user, seconds), Duration.ofSeconds(seconds));
        if (!ok) {
            return LockResult.failed(LockResult.FailureReason.ALREADY_LOCKED);
        }
        return LockResult.success(new LockToken(tokenId, ensureInfo(lockInfo, user), timeout));
    }

    @Override
    public LockResult refresh(String tokenId, LockTimeout timeout, LockableResource resource) {
        Slot slot = slot(resource);
        long seconds = resolveSeconds(timeout);
        String current = messaging.valueGet(slot.tenant(), slot.subKey()).orElse(null);
        if (current == null) {
            // Lenient: some clients send etags instead of tokens, or the lock
            // TTL lapsed mid-session. Re-establish rather than fail the write.
            return lock(timeout, ensureInfo(null, ""), resource);
        }
        Parsed parsed = decode(current);
        // A live lock may only be refreshed by its own token holder. Ignoring the
        // presented tokenId would let any WRITE-holder refresh someone else's lock
        // AND receive the owner's real token back (→ steal/unlock it), defeating
        // the exclusive-lock contract. Mismatch → ALREADY_LOCKED, no token leak.
        if (!parsed.tokenId().equals(tokenId)) {
            return LockResult.failed(LockResult.FailureReason.ALREADY_LOCKED);
        }
        messaging.valuePut(
                slot.tenant(),
                slot.subKey(),
                encode(parsed.tokenId(), parsed.user(), seconds),
                Duration.ofSeconds(seconds));
        return LockResult.success(new LockToken(parsed.tokenId(), ensureInfo(null, parsed.user()), timeout));
    }

    @Override
    public void unlock(String tokenId, LockableResource resource) {
        Slot slot = slot(resource);
        String current = messaging.valueGet(slot.tenant(), slot.subKey()).orElse(null);
        if (current == null) {
            return;
        }
        Parsed parsed = decode(current);
        if (parsed.tokenId().equals(tokenId)) {
            messaging.valueDelete(slot.tenant(), slot.subKey());
        }
        // Non-matching token: lenient no-op (a stale token after a pod restart
        // must not wedge the client). Integrity is guarded by @Version anyway.
    }

    @Override
    public @Nullable LockToken getCurrentToken(LockableResource resource) {
        Slot slot = slot(resource);
        String current = messaging.valueGet(slot.tenant(), slot.subKey()).orElse(null);
        if (current == null) {
            return null;
        }
        Parsed parsed = decode(current);
        LockToken token = new LockToken();
        token.tokenId = parsed.tokenId();
        token.info = ensureInfo(null, parsed.user());
        token.timeout = new LockTimeout(parsed.seconds());
        return token;
    }

    private long resolveSeconds(@Nullable LockTimeout timeout) {
        long def = properties.getLockTimeout().toSeconds();
        long max = properties.getLockTimeoutMax().toSeconds();
        if (timeout == null) {
            return def;
        }
        Long requested = timeout.getSeconds();
        if (requested == null || requested <= 0) {
            return def;
        }
        if (requested == Long.MAX_VALUE) {
            return max;
        }
        return Math.min(requested, max);
    }

    private static LockInfo ensureInfo(@Nullable LockInfo info, String user) {
        if (info != null) {
            if (info.lockedByUser == null) {
                info.lockedByUser = user;
            }
            return info;
        }
        return new LockInfo(LockInfo.LockScope.EXCLUSIVE, LockInfo.LockType.WRITE, user, LockInfo.LockDepth.ZERO);
    }

    private static String encode(String tokenId, String user, long seconds) {
        return tokenId + SEP + user + SEP + seconds;
    }

    private static Parsed decode(String raw) {
        String[] parts = raw.split("\n", -1);
        String tokenId = parts.length > 0 ? parts[0] : "";
        String user = parts.length > 1 ? parts[1] : "";
        long seconds = 3600;
        if (parts.length > 2) {
            try {
                seconds = Long.parseLong(parts[2]);
            } catch (NumberFormatException ignored) {
                // keep default
            }
        }
        return new Parsed(tokenId, user, seconds);
    }

    /** Where the lock of {@code resource} lives: its tenant and a sub-key stable across content writes. */
    private Slot slot(LockableResource resource) {
        if (resource instanceof AbstractDavResource dav) {
            String composite = dav.coords().tenantId() + " " + dav.coords().project() + " "
                    + dav.coords().path();
            String encoded =
                    Base64.getUrlEncoder().withoutPadding().encodeToString(composite.getBytes(StandardCharsets.UTF_8));
            return new Slot(dav.coords().tenantId(), "webdav:lock:" + encoded);
        }
        return new Slot(VanceRedisMessagingService.GLOBAL, "webdav:lock:" + resource.getUniqueId());
    }

    private record Slot(String tenant, String subKey) {}

    private record Parsed(String tokenId, String user, long seconds) {}
}
