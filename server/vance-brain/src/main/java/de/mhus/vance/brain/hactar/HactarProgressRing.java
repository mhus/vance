package de.mhus.vance.brain.hactar;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * In-memory ring of the last {@code vance.process.progress(...)} notes of a
 * Hactar run — the read substance behind mid-run "Meldung" turns, the agent's
 * prompt status block and the {@code //hactar} diagnostics
 * (planning/hactar-agent-identity.md §3.1, F3).
 *
 * <p>Recorded from the shared {@code ExecutingPhase} progress bridge, so the
 * ring fills in <b>both</b> modes (headless and session) — the headless code
 * path is not rerouted through the run service for this. The ring is
 * deliberately volatile and unpersisted: the pending-message wakeup stays the
 * wake channel, the ring is only what an answering reader sees. The live
 * progress channel itself (ProgressEmitter) remains ephemeral per spec.
 *
 * <p>Capped twice: 20 entries per process and 500 chars per entry text — a
 * misbehaving script cannot ship unbounded memory or strings through the
 * ring.
 */
@Component
public class HactarProgressRing {

    private static final int MAX_ENTRIES = 20;
    private static final int MAX_ENTRY_CHARS = 500;

    /** One progress note — immutable snapshot of message + flattened payload. */
    public record Entry(Instant at, String text) {}

    private final Map<String, Deque<Entry>> rings = new ConcurrentHashMap<>();

    /**
     * Records one progress note. Payload is flattened the same way the
     * progress bridge flattens it for the status channel ("k=v, k=v").
     */
    public void record(String processId, String message, @Nullable Map<String, Object> payload) {
        if (processId == null || processId.isBlank() || message == null || message.isBlank()) {
            return;
        }
        String text = message;
        if (payload != null && !payload.isEmpty()) {
            StringBuilder sb = new StringBuilder(message);
            sb.append(" — ");
            boolean first = true;
            for (Map.Entry<String, Object> e : payload.entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(e.getKey()).append('=').append(e.getValue());
                if (sb.length() > MAX_ENTRY_CHARS) break;
            }
            text = sb.length() > MAX_ENTRY_CHARS ? sb.substring(0, MAX_ENTRY_CHARS - 1) + "…" : sb.toString();
        } else if (text.length() > MAX_ENTRY_CHARS) {
            text = text.substring(0, MAX_ENTRY_CHARS - 1) + "…";
        }
        Deque<Entry> ring = rings.computeIfAbsent(processId, id -> new ArrayDeque<>());
        synchronized (ring) {
            ring.addLast(new Entry(Instant.now(), text));
            while (ring.size() > MAX_ENTRIES) {
                ring.removeFirst();
            }
        }
    }

    /** The last {@code max} notes, oldest first — empty when none were recorded. */
    public List<Entry> tail(String processId, int max) {
        Deque<Entry> ring = rings.get(processId);
        if (ring == null) {
            return List.of();
        }
        synchronized (ring) {
            List<Entry> out = new ArrayList<>(ring);
            int from = Math.max(0, out.size() - Math.max(0, max));
            return List.copyOf(out.subList(from, out.size()));
        }
    }

    /** Drops the ring (called when a run's process is cleaned up or restarted fresh). */
    public void clear(String processId) {
        Deque<Entry> ring = rings.remove(processId);
        if (ring != null) {
            synchronized (ring) {
                ring.clear();
            }
        }
    }
}
