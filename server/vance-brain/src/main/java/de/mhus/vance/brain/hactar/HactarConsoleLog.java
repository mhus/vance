package de.mhus.vance.brain.hactar;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * In-memory ring of the CURRENT run's console lines — the live half of the
 * console visibility (Live-Fund 5): the persisted {@code consoleTail} on
 * the state only exists at the terminal transition, so mid-run "how is it
 * going?" turns saw nothing of the running script (and a stale tail from
 * the previous run, because the kick didn't reset it).
 *
 * <p>Fed from the executor's line tap via the {@code ExecutingPhase} bridge
 * (the same wiring the progress ring uses), read by the session identity's
 * status block and {@code //hactar}. Capped at 100 lines per process; a
 * fresh kick clears the ring. Like the progress ring it is deliberately
 * volatile — the pending-message wakeup stays the wake channel, the ring is
 * only what an answering reader sees.
 */
@Component
public class HactarConsoleLog {

    private static final int MAX_LINES = 100;

    /** One console line — arrival time + text (line separator stripped). */
    public record Line(Instant at, String text) {}

    private static final class Ring {
        final Deque<Line> lines = new ArrayDeque<>();
        volatile boolean started = false;
    }

    private final Map<String, Ring> rings = new ConcurrentHashMap<>();

    /** Marks the run live and clears previous lines (called on every kick). */
    public void startRun(String processId) {
        Ring ring = rings.computeIfAbsent(processId, id -> new Ring());
        synchronized (ring.lines) {
            ring.lines.clear();
            ring.started = true;
        }
    }

    /** Marks the run finished — the ring's lines stay readable for later
     *  "what did it print?" questions until the next kick. */
    public void finishRun(String processId) {
        Ring ring = rings.get(processId);
        if (ring != null) {
            ring.started = false;
        }
    }

    /** Records one console line (executor line tap). */
    public void record(String processId, String line) {
        if (processId == null || processId.isBlank() || line == null || line.isBlank()) {
            return;
        }
        Ring ring = rings.computeIfAbsent(processId, id -> new Ring());
        String text = line.stripTrailing();
        synchronized (ring.lines) {
            ring.lines.addLast(new Line(Instant.now(), text));
            while (ring.lines.size() > MAX_LINES) {
                ring.lines.removeFirst();
            }
        }
    }

    /** The last {@code max} lines, oldest first — empty when none. */
    public List<Line> tail(String processId, int max) {
        Ring ring = rings.get(processId);
        if (ring == null) {
            return List.of();
        }
        synchronized (ring.lines) {
            List<Line> out = new ArrayList<>(ring.lines);
            int from = Math.max(0, out.size() - Math.max(0, max));
            return List.copyOf(out.subList(from, out.size()));
        }
    }

    /**
     * The last {@code max} lines as a plain block, each prefixed with its
     * HH:mm:ss arrival time (Live-Fund 5a): models anchor on their own
     * earlier replies when asked for a status again — the per-line
     * wall-clock stamp makes the freshness of the current block
     * undeniable against the conversation transcript. Empty string
     * when the process has no lines.
     */
    public String renderTail(String processId, int max) {
        List<Line> lines = tail(processId, max);
        if (lines.isEmpty()) {
            return "";
        }
        DateTimeFormatter stamp = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
        StringBuilder sb = new StringBuilder();
        for (Line line : lines) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(stamp.format(line.at())).append("  ").append(line.text());
        }
        return sb.toString();
    }

    /** Whether a run is currently live (for status rendering). */
    public boolean isRunning(String processId) {
        Ring ring = rings.get(processId);
        return ring != null && ring.started;
    }
}
