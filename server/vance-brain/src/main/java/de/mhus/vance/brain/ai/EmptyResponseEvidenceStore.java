package de.mhus.vance.brain.ai;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Bounded in-memory hold for {@link EmptyResponseEvidence} wire captures —
 * the meeting point between the HTTP layer that records and the
 * diagnostic layer that reports.
 *
 * <p>The two sides share no object boundary: the recording HttpClient
 * decorator is built deep inside the provider, the
 * {@link EmptyResponseDiagnosticSink} fires in the resilience layer, and
 * neither can hand the other anything at construction time. A singleton
 * store bridges them without introducing a dependency edge — the recorder
 * registers, the diagnostic drains, both stay decoupled and the captures
 * live nowhere else.
 *
 * <p>Deliberately tiny and strictly bounded: empty responses are rare, and
 * a capture is small (the response was empty — that is the whole point),
 * but the store must stay safe under pathological conditions, so it keeps
 * at most {@link #MAX_ENTRIES} captures and forgets anything older than
 * {@link #MAX_AGE}. A capture that never gets reported (no sink attached,
 * diagnostics disabled) simply expires — no leak, no growth.
 *
 * <p>Pod-local by design. The sink fires on the same pod whose HTTP layer
 * produced the empty response, so a local store always holds the right
 * evidence; cross-pod setups never see each other's captures, which is
 * exactly the desired scope.
 */
@Component
public class EmptyResponseEvidenceStore {

    /** Upper bound on held captures — evicts oldest first. */
    static final int MAX_ENTRIES = 8;

    /** Retention: a report fires seconds after its last empty attempt, so
     * minutes of headroom cover even a long retry-backoff chain. */
    static final Duration MAX_AGE = Duration.ofMinutes(10);

    private final ArrayDeque<EmptyResponseEvidence> entries = new ArrayDeque<>();
    private final Clock clock;

    public EmptyResponseEvidenceStore() {
        this(Clock.systemUTC());
    }

    /** Test seam — a fixed clock makes the age window assertable. */
    EmptyResponseEvidenceStore(Clock clock) {
        this.clock = clock;
    }

    /**
     * Holds one capture, evicting oldest beyond {@link #MAX_ENTRIES}.
     * Registering is the recorder's only job — matching a capture to a
     * report is the drain side's concern, so no filtering happens here.
     */
    public void register(EmptyResponseEvidence evidence) {
        synchronized (entries) {
            purgeExpiredLocked();
            entries.addLast(evidence);
            while (entries.size() > MAX_ENTRIES) {
                entries.pollFirst();
            }
        }
    }

    /**
     * Removes and returns every capture that matches {@code modelLabel}
     * and is within {@link #MAX_AGE}. Draining — not peeking — keeps each
     * capture attached to at most one report; a later report for the same
     * model sees only its own occurrences, never re-served evidence.
     *
     * <p>Concurrent empty responses on the same model (two tenants, two
     * processes) can cross-attach: both look identical from the wire. The
     * captures carry timestamps and URLs, so a mismatch is visible in the
     * transcript, and the alternative — session-scoped correlation
     * plumbing through the provider stack — costs more than the residual
     * ambiguity of a rare diagnostic.
     */
    public List<EmptyResponseEvidence> drainRecent(String modelLabel) {
        synchronized (entries) {
            purgeExpiredLocked();
            List<EmptyResponseEvidence> out = new ArrayList<>();
            Iterator<EmptyResponseEvidence> it = entries.iterator();
            while (it.hasNext()) {
                EmptyResponseEvidence evidence = it.next();
                if (evidence.matchesModelLabel(modelLabel)) {
                    out.add(evidence);
                    it.remove();
                }
            }
            return out;
        }
    }

    /** Drops lapsed captures. Must be called under the {@code entries} lock. */
    private void purgeExpiredLocked() {
        Instant cutoff = clock.instant().minus(MAX_AGE);
        entries.removeIf(evidence -> evidence.capturedAt().isBefore(cutoff));
    }
}
