package de.mhus.vance.brain.daemon;

import de.mhus.vance.api.tools.ToolSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

/**
 * Per-pod, project-scoped registry of {@code profile=daemon} connections.
 * A daemon is identified by {@code (tenantId, projectId, daemonName)} and
 * its registry entry lives as long as its WS session.
 *
 * <p>Project-scope rather than tenant-scope is deliberate: projects have
 * a home pod, all chat-sessions in the project land on that pod, the
 * daemon lands on the same pod via the existing project-routing logic.
 * Cross-session invokes become a pure in-memory lookup, no inter-pod
 * messaging needed.
 *
 * <p>Collision policy is <strong>first-wins</strong>: if a second foot
 * tries to register with the same {@code daemonName}, the existing
 * entry stays, the second registration is rejected. Silent-replace
 * would be a leiser DoS vector — anyone with WS access could hijack a
 * running daemon. See {@code planning/foot-daemon-tools.md} §5.3.
 *
 * <p>Lookups happen by key (for invokes) and by WebSocket session (for
 * disconnect cleanup).
 */
@Service
@Slf4j
public class DaemonRegistry {

    /**
     * Composite key. Tenant is included so a tenant-shared pod (rare,
     * but possible for `_tenant` projects) doesn't accidentally collide
     * names across tenants.
     */
    public record DaemonKey(String tenantId, String projectId, String daemonName) {
        public DaemonKey {
            if (tenantId == null || tenantId.isBlank()) {
                throw new IllegalArgumentException("tenantId required");
            }
            if (projectId == null || projectId.isBlank()) {
                throw new IllegalArgumentException("projectId required");
            }
            if (daemonName == null || daemonName.isBlank()) {
                throw new IllegalArgumentException("daemonName required");
            }
        }
    }

    /**
     * One registered daemon.
     *
     * <p>{@link #stale} flips to {@code true} when the daemon's WS closes.
     * The entry is kept around for {@link #staleTtl} so a reconnect-flicker
     * doesn't drop sub-tools from the chat-session listing; invokes against
     * a stale daemon throw a clear "offline" exception (see
     * {@link FootDaemonToolFactory}). A scheduled sweep removes stale
     * entries after the TTL.
     *
     * <p>{@link #disconnectedAt} is set only when {@link #stale} is true.
     * {@link #lastSeenAt} is bumped on every heartbeat / manifest-refresh
     * while online.
     */
    public record DaemonRef(
            DaemonKey key,
            WebSocketSession wsSession,
            Map<String, ToolSpec> manifest,
            Instant registeredAt,
            Instant lastSeenAt,
            boolean stale,
            @Nullable Instant disconnectedAt) {

        /** Compact 5-arg constructor for callers that don't care about stale-state. */
        public DaemonRef(
                DaemonKey key, WebSocketSession wsSession,
                Map<String, ToolSpec> manifest,
                Instant registeredAt, Instant lastSeenAt) {
            this(key, wsSession, manifest, registeredAt, lastSeenAt, false, null);
        }
    }

    private final Map<DaemonKey, DaemonRef> byKey = new ConcurrentHashMap<>();
    // Reverse index: WS-session → key. Lets the close-handler unregister
    // in O(1) without scanning the entire registry.
    private final Map<WebSocketSession, DaemonKey> byWs = new ConcurrentHashMap<>();

    /**
     * How long a daemon's stale entry survives after disconnect before
     * being swept. {@code 0} = drop immediately on disconnect (no
     * reconnect-flicker grace). Default 60 seconds — covers a foot
     * restart + JWT-mint round-trip comfortably.
     */
    @Value("${vance.daemon.stale-ttl-seconds:60}")
    long staleTtlSeconds = 60;

    // Pending cross-session tool invocations keyed by correlation id. The
    // result handler completes these via {@link #completeInvocation}; the
    // FootDaemon tool sources allocate them via {@link #beginInvocation}.
    // Lives here (not in ClientToolRegistry) because the pending lifecycle
    // is tied to the daemon's WS, not to any chat session.
    private final Map<String, DaemonPending> pendingByCorr = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong corrSeq =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * Attempts to register {@code key} for {@code wsSession} with the
     * given manifest. Returns the resulting entry on success, or
     * {@link Optional#empty} when a different connection already owns
     * the key (first-wins policy). The WS-session caller should not
     * close on a collision — let the foot's retry logic figure it out.
     */
    public Optional<DaemonRef> register(
            DaemonKey key, WebSocketSession wsSession, List<ToolSpec> tools) {
        Instant now = Instant.now();
        Map<String, ToolSpec> manifest = indexByName(tools);

        // First-wins collision: a different LIVE session holding the key
        // blocks the new registration. Stale entries (kept around after
        // disconnect for reconnect-flicker grace) are replaceable — the
        // old wsSession is already closed, the stale entry is just a
        // listing placeholder.
        DaemonRef existing = byKey.get(key);
        if (existing != null && existing.wsSession() != wsSession && !existing.stale()) {
            log.warn("DaemonRegistry collision: {} already held by another live connection — "
                            + "rejecting new registration",
                    key);
            return Optional.empty();
        }

        boolean wasStale = existing != null && existing.stale();
        DaemonRef ref = new DaemonRef(
                key, wsSession, manifest,
                existing == null ? now : existing.registeredAt(),
                now);
        byKey.put(key, ref);
        byWs.put(wsSession, key);
        if (existing == null) {
            log.info("DaemonRegistry registered {} tools={}", key, manifest.keySet());
        } else if (wasStale) {
            log.info("DaemonRegistry reconnect: {} replaced stale entry tools={}", key, manifest.keySet());
        } else {
            log.info("DaemonRegistry refreshed manifest for {} tools={}", key, manifest.keySet());
        }
        return Optional.of(ref);
    }

    /**
     * Removes the registration for the daemon backed by {@code wsSession},
     * if any. Idempotent — called from the WS close-handler for every
     * connection (most aren't daemons). Returns the key that was
     * removed (or empty when {@code wsSession} did not own a daemon
     * registration) so the caller can trigger cache invalidation in the
     * project's server-tool registry.
     *
     * <p>Any pending tool invocations against this daemon are completed
     * exceptionally so the chat session waiting on them gets a clear
     * error instead of timing out.
     */
    public Optional<DaemonKey> unregister(WebSocketSession wsSession) {
        DaemonKey key = byWs.remove(wsSession);
        if (key == null) return Optional.empty();
        DaemonRef ref = byKey.get(key);
        Instant now = Instant.now();
        // Only mutate the by-key entry when it still points at THIS
        // wsSession — covers a race where a fresh registration replaced
        // the entry between close-event ordering.
        if (ref != null && ref.wsSession() == wsSession) {
            if (staleTtlSeconds <= 0) {
                byKey.remove(key);
            } else {
                // Mark stale so listings keep the sub-tools visible briefly;
                // invokes fail fast (factory checks the stale flag). Sweep
                // drops the entry once {@link #staleTtlSeconds} has passed.
                byKey.put(key, new DaemonRef(
                        ref.key(), ref.wsSession(), ref.manifest(),
                        ref.registeredAt(), now,
                        true, now));
            }
        }
        // Fail any in-flight invocations that targeted this daemon — they
        // are tied to the now-dead WS and the future will never complete.
        List<String> toFail = new ArrayList<>();
        for (Map.Entry<String, DaemonPending> e : pendingByCorr.entrySet()) {
            if (e.getValue().daemonKey().equals(key)) toFail.add(e.getKey());
        }
        for (String corr : toFail) {
            DaemonPending p = pendingByCorr.remove(corr);
            if (p != null) {
                p.future().completeExceptionally(new java.io.IOException(
                        "daemon '" + key.daemonName() + "' disconnected during invocation"));
            }
        }
        log.info("DaemonRegistry unregistered {}{}{}", key,
                staleTtlSeconds > 0 ? " (stale, ttl=" + staleTtlSeconds + "s)" : "",
                toFail.isEmpty() ? "" : " — failed " + toFail.size() + " pending");
        return Optional.of(key);
    }

    /**
     * Periodic sweep dropping stale entries whose grace period has
     * elapsed. Triggers a cache-invalidation hook (see
     * {@link #onStaleDrop}) so the project's server-tool registry can
     * refresh listings without the now-gone sub-tools.
     */
    @Scheduled(fixedDelayString = "PT15S")
    void sweepStaleEntries() {
        if (staleTtlSeconds <= 0 || byKey.isEmpty()) return;
        Instant cutoff = Instant.now().minus(Duration.ofSeconds(staleTtlSeconds));
        List<DaemonKey> dropped = new ArrayList<>();
        for (Map.Entry<DaemonKey, DaemonRef> e : byKey.entrySet()) {
            DaemonRef ref = e.getValue();
            if (!ref.stale()) continue;
            Instant disconnectedAt = ref.disconnectedAt();
            if (disconnectedAt != null && disconnectedAt.isBefore(cutoff)) {
                if (byKey.remove(e.getKey(), ref)) {
                    dropped.add(e.getKey());
                }
            }
        }
        for (DaemonKey key : dropped) {
            log.info("DaemonRegistry swept stale daemon {} (ttl={}s elapsed)",
                    key, staleTtlSeconds);
            onStaleDrop.accept(key);
        }
    }

    /**
     * Hook invoked once for every stale daemon dropped by
     * {@link #sweepStaleEntries}. Used by the brain-side wiring to
     * refresh the project's server-tool registry so vanished sub-tools
     * disappear from listings. Tests can swap a no-op or a probe.
     */
    private volatile java.util.function.Consumer<DaemonKey> onStaleDrop = key -> { };

    /** Wires a callback for the stale-sweep — set by the WS-handler at boot. */
    public void setOnStaleDrop(java.util.function.Consumer<DaemonKey> cb) {
        this.onStaleDrop = cb == null ? key -> { } : cb;
    }

    /** Bump {@code lastSeenAt} (used by heartbeat / message activity). */
    public void touch(WebSocketSession wsSession) {
        DaemonKey key = byWs.get(wsSession);
        if (key == null) return;
        byKey.computeIfPresent(key, (k, prev) -> new DaemonRef(
                prev.key(), prev.wsSession(), prev.manifest(),
                prev.registeredAt(), Instant.now()));
    }

    /** Look up by composite key — used by the {@code foot_daemon} ServerTool when invoking. */
    public Optional<DaemonRef> find(DaemonKey key) {
        return Optional.ofNullable(byKey.get(key));
    }

    /** Convenience overload. */
    public Optional<DaemonRef> find(String tenantId, String projectId, String daemonName) {
        return find(new DaemonKey(tenantId, projectId, daemonName));
    }

    /** All daemons in a project — used by the admin endpoint and Insights. */
    public List<DaemonRef> listInProject(String tenantId, String projectId) {
        List<DaemonRef> out = new ArrayList<>();
        for (DaemonRef ref : byKey.values()) {
            DaemonKey k = ref.key();
            if (k.tenantId().equals(tenantId) && k.projectId().equals(projectId)) {
                out.add(ref);
            }
        }
        return out;
    }

    /** All daemons in the tenant across projects — admin overview. */
    public List<DaemonRef> listInTenant(String tenantId) {
        List<DaemonRef> out = new ArrayList<>();
        for (DaemonRef ref : byKey.values()) {
            if (ref.key().tenantId().equals(tenantId)) out.add(ref);
        }
        return out;
    }

    /** Total entries — useful for tests / sanity. */
    public int size() {
        return byKey.size();
    }

    /** Visible for tests only — clears all state. */
    void clearForTests() {
        byKey.clear();
        byWs.clear();
        pendingByCorr.clear();
    }

    // ─── Pending invocation lifecycle ───────────────────────────────────

    /**
     * Allocates a correlation id + future for a pending tool invocation
     * against {@code key}. Caller writes the {@code client-tool-invoke}
     * envelope, then awaits the future with a timeout. If the daemon
     * disconnects before the result arrives, {@link #unregister} fails
     * the future for the caller.
     */
    public DaemonPending beginInvocation(DaemonKey key, String toolName) {
        String corr = "dt-" + corrSeq.incrementAndGet();
        DaemonPending p = new DaemonPending(
                corr, key, toolName,
                new java.util.concurrent.CompletableFuture<>(),
                Instant.now());
        pendingByCorr.put(corr, p);
        return p;
    }

    /**
     * Matches an incoming {@code client-tool-result} to a pending future
     * and completes it. Returns empty when the correlation id is
     * unknown (late result after timeout, or daemon-issued for a chat
     * session that the {@link de.mhus.vance.brain.tools.client.ClientToolRegistry}
     * owns).
     */
    public Optional<DaemonPending> completeInvocation(
            String correlationId, @Nullable Map<String, Object> result, @Nullable String error) {
        DaemonPending p = pendingByCorr.remove(correlationId);
        if (p == null) return Optional.empty();
        if (error != null) {
            p.future().completeExceptionally(new java.io.IOException(
                    "daemon tool '" + p.toolName() + "' failed: " + error));
        } else {
            p.future().complete(result == null ? Map.of() : result);
        }
        return Optional.of(p);
    }

    /** Cancels a pending invocation — used on timeout. */
    public void cancel(String correlationId, String reason) {
        DaemonPending p = pendingByCorr.remove(correlationId);
        if (p != null) {
            p.future().completeExceptionally(new java.io.IOException(reason));
        }
    }

    /** One in-flight invocation against a daemon. */
    public record DaemonPending(
            String correlationId,
            DaemonKey daemonKey,
            String toolName,
            java.util.concurrent.CompletableFuture<Map<String, Object>> future,
            Instant createdAt) {}

    private static Map<String, ToolSpec> indexByName(@Nullable List<ToolSpec> tools) {
        if (tools == null || tools.isEmpty()) return Map.of();
        Map<String, ToolSpec> out = new LinkedHashMap<>();
        for (ToolSpec t : tools) {
            if (t == null || t.getName() == null) continue;
            out.put(t.getName(), t);
        }
        return Map.copyOf(out);
    }
}
