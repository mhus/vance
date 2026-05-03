package de.mhus.vance.foot.connection.handlers;

import de.mhus.vance.api.progress.MetricsPayload;
import de.mhus.vance.api.progress.PlanNode;
import de.mhus.vance.api.progress.PlanPayload;
import de.mhus.vance.api.progress.ProcessProgressNotification;
import de.mhus.vance.api.progress.ProgressKind;
import de.mhus.vance.api.progress.StatusPayload;
import de.mhus.vance.api.progress.StatusTag;
import de.mhus.vance.api.progress.UsageDelta;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.foot.connection.MessageHandler;
import de.mhus.vance.foot.ui.BusyIndicator;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.StreamingDisplay;
import de.mhus.vance.foot.ui.Verbosity;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Renders {@code process-progress} side-channel notifications. One
 * {@link ProcessProgressNotification} envelope arrives per metrics tick,
 * plan mutation, or status ping; the {@code kind} discriminator picks
 * the rendering.
 *
 * <p>v1 is a flat textual renderer through {@link ChatTerminal}: a HUD
 * line for metrics, a one-line phase summary for plan, a toast-style
 * line for status. Lines are styled faint-gray via JLine's
 * {@link AttributedStyle} so they sit visually behind the chat. A
 * future TUI overlay (split-screen with Lanterna) can swap this
 * implementation without touching the wire contract.
 */
@Component
public class ProcessProgressHandler implements MessageHandler {

    /**
     * Bright-black ("gray") — the "side-channel" tone. One notch
     * brighter than {@code bright-black + faint}, which read as
     * almost-invisible on most modern terminals.
     */
    private static final AttributedStyle DIM_STYLE = AttributedStyle.DEFAULT
            .foreground(AttributedStyle.BRIGHT + AttributedStyle.BLACK);

    private final ChatTerminal terminal;
    private final StreamingDisplay streaming;
    private final BusyIndicator busyIndicator;
    private final ObjectMapper json = JsonMapper.builder().build();

    /**
     * Wall-clock start of every in-flight operation, keyed by
     * {@code operationId}. Filled on open-tags ({@link StatusTag#TOOL_START},
     * {@link StatusTag#DELEGATING}); drained on close-tags. End-to-end
     * wall-clock measured here is the latency the user actually sees —
     * server-side {@code usage.elapsedMs} reflects only brain-internal time.
     */
    private final Map<String, Instant> operationStarts = new ConcurrentHashMap<>();

    /**
     * Set of process-ids that currently have an open
     * {@link StatusTag#ENGINE_TURN_START} we entered into the
     * {@link BusyIndicator}. We track them so we can both deduplicate
     * (a duplicate START shouldn't double-enter) and exit cleanly when
     * the matching END arrives — even if it gets delivered out of the
     * normal pair-order under WS retries.
     */
    private final Set<String> activeTurns = new HashSet<>();

    public ProcessProgressHandler(
            ChatTerminal terminal,
            StreamingDisplay streaming,
            BusyIndicator busyIndicator) {
        this.terminal = terminal;
        this.streaming = streaming;
        this.busyIndicator = busyIndicator;
    }

    @Override
    public String messageType() {
        return MessageType.PROCESS_PROGRESS;
    }

    @Override
    public void handle(WebSocketEnvelope envelope) {
        ProcessProgressNotification msg = json.convertValue(
                envelope.getData(), ProcessProgressNotification.class);
        if (msg == null || msg.getKind() == null) return;
        // Spinner tracking runs UNCONDITIONALLY — the user's verbosity
        // filter only controls visible rendering, not the busy state.
        // Otherwise a "verbosity=info" user would see a stuck spinner
        // because the engine_turn_end was filtered out.
        if (msg.getKind() == ProgressKind.STATUS && msg.getStatus() != null) {
            updateBusyState(msg);
        }
        // Skip the visible-render pipeline (including the streaming-
        // suspend newline) when the user's verbosity threshold would
        // filter this kind out. Otherwise we'd emit a stray newline
        // that unhooks the in-flight chat stream from its onCommit and
        // makes ChatMessageAppendedHandler re-render the canonical
        // text — visible duplicate.
        if (!terminal.threshold().shows(verbosityFor(msg.getKind()))) {
            return;
        }
        // Terminate any in-flight chat stream first so the side-channel
        // line lands on its own line. The next chat chunk re-emits its
        // role header on a fresh line.
        streaming.suspend();
        String src = formatSource(msg);
        switch (msg.getKind()) {
            case METRICS -> renderMetrics(src, msg.getMetrics());
            case PLAN -> renderPlan(src, msg.getPlan());
            case STATUS -> renderStatus(src, msg.getStatus());
        }
    }

    /**
     * Couples engine-turn STATUS messages to the
     * {@link BusyIndicator} so the spinner stays alive while
     * worker turns run async after the original chat round-trip
     * has already returned.
     *
     * <p>Counter-based via the indicator's enter/exit; a per-process
     * de-duplication set guards against double-START / double-END
     * events (rare under retries / reconnect).
     */
    private void updateBusyState(ProcessProgressNotification msg) {
        StatusPayload status = msg.getStatus();
        StatusTag tag = status.getTag();
        if (tag == null) return;
        String processId = msg.getProcessId();
        if (processId == null || processId.isBlank()) return;
        switch (tag) {
            case ENGINE_TURN_START -> {
                synchronized (activeTurns) {
                    if (activeTurns.add(processId)) {
                        busyIndicator.enter("engine_turn_start:" + safeName(msg));
                    }
                }
            }
            case ENGINE_TURN_END -> {
                synchronized (activeTurns) {
                    if (activeTurns.remove(processId)) {
                        busyIndicator.exit("engine_turn_end:" + safeName(msg));
                    }
                }
            }
            default -> { /* tool_start/end, plan tags etc. don't affect busy */ }
        }
    }

    private static String safeName(ProcessProgressNotification msg) {
        String n = msg.getProcessName();
        if (n != null && !n.isBlank()) return n;
        String id = msg.getProcessId();
        return id == null ? "?" : id;
    }

    private static Verbosity verbosityFor(ProgressKind kind) {
        // Mirror the per-renderer choices below — keep these in sync.
        return switch (kind) {
            case METRICS -> Verbosity.VERBOSE;
            case PLAN, STATUS -> Verbosity.INFO;
        };
    }

    private void renderMetrics(String src, @Nullable MetricsPayload m) {
        if (m == null) return;
        // HUD-style one-liner. Verbosity VERBOSE so the user can mute
        // at INFO if the per-roundtrip cadence gets too chatty.
        String line = String.format(
                "[hud] %s · %d calls · %s in / %s out · %.1fs%s",
                src,
                m.getLlmCallCount(),
                formatTokens(m.getTokensInTotal()),
                formatTokens(m.getTokensOutTotal()),
                m.getElapsedMs() / 1000.0,
                m.getModelAlias() == null ? "" : " · " + m.getModelAlias());
        terminal.printlnStyled(Verbosity.VERBOSE, dim(line));
    }

    private void renderPlan(String src, @Nullable PlanPayload p) {
        if (p == null || p.getRootNode() == null) return;
        Map<String, Integer> counts = new HashMap<>();
        countByStatus(p.getRootNode(), counts);
        StringBuilder sb = new StringBuilder();
        sb.append("[plan] ").append(src)
                .append(" · ").append(p.getRootNode().getTitle())
                .append(" [").append(p.getRootNode().getStatus()).append("]");
        boolean first = true;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            sb.append(first ? " — " : ", ");
            first = false;
            sb.append(e.getKey()).append("=").append(e.getValue());
        }
        terminal.printlnStyled(Verbosity.INFO, dim(sb.toString()));
    }

    private void renderStatus(String src, @Nullable StatusPayload s) {
        if (s == null) return;
        StatusTag tag = s.getTag();
        String operationId = s.getOperationId();
        Duration wallClock = trackOperation(tag, operationId);

        StringBuilder line = new StringBuilder();
        line.append('[').append(tag == null ? "?" : tag.name().toLowerCase()).append("] ")
                .append(src)
                .append(" · ")
                .append(s.getText() == null ? "" : s.getText());

        if (wallClock != null) {
            line.append(" · ").append(formatSeconds(wallClock.toMillis())).append(" wall");
        }
        appendUsage(line, s.getUsage());

        terminal.printlnStyled(Verbosity.INFO, dim(line.toString()));
    }

    /**
     * Returns the end-to-end wall-clock for a closing tag, or {@code null}
     * if this status doesn't terminate a tracked operation. Open-tags get
     * stored; close-tags get drained.
     */
    private @Nullable Duration trackOperation(@Nullable StatusTag tag, @Nullable String operationId) {
        if (tag == null || operationId == null || operationId.isBlank()) {
            return null;
        }
        if (isOpenTag(tag)) {
            operationStarts.put(operationId, Instant.now());
            return null;
        }
        if (isCloseTag(tag)) {
            Instant started = operationStarts.remove(operationId);
            if (started == null) return null;
            return Duration.between(started, Instant.now());
        }
        return null;
    }

    private static boolean isOpenTag(StatusTag tag) {
        return tag == StatusTag.TOOL_START || tag == StatusTag.DELEGATING;
    }

    private static boolean isCloseTag(StatusTag tag) {
        return tag == StatusTag.TOOL_END
                || tag == StatusTag.NODE_DONE
                || tag == StatusTag.PHASE_DONE;
    }

    private static void appendUsage(StringBuilder line, @Nullable UsageDelta u) {
        if (u == null) return;
        // Token block only when an LLM was actually involved — otherwise
        // the zero counts add noise (filesystem tools etc.).
        if (u.getLlmCalls() > 0 || u.getTokensIn() > 0 || u.getTokensOut() > 0) {
            line.append(" · ")
                    .append(formatTokens(u.getTokensIn())).append(" in / ")
                    .append(formatTokens(u.getTokensOut())).append(" out");
            if (u.getLlmCalls() > 1) {
                line.append(" · ").append(u.getLlmCalls()).append(" calls");
            }
        }
        if (u.getModelAlias() != null && !u.getModelAlias().isBlank()) {
            line.append(" · ").append(u.getModelAlias());
        }
        if (u.getCostMicros() != null && u.getCostMicros() > 0) {
            line.append(" · ").append(formatCost(u.getCostMicros()));
        }
    }

    private static String formatSeconds(long ms) {
        if (ms < 1_000) return ms + "ms";
        return String.format("%.1fs", ms / 1_000.0);
    }

    private static String formatCost(long micros) {
        // micros = 1/1_000_000 EUR — render with two significant digits.
        double euros = micros / 1_000_000.0;
        if (euros >= 1.0) return String.format("€%.2f", euros);
        if (euros >= 0.01) return String.format("%.0f¢", euros * 100);
        return String.format("%.2f¢", euros * 100);
    }

    private static AttributedString dim(String text) {
        return new AttributedStringBuilder()
                .style(DIM_STYLE)
                .append(text)
                .toAttributedString();
    }

    private static String formatSource(ProcessProgressNotification msg) {
        // Prefer the human title when available, fall back to the
        // technical name. Engine name in parens so the user can tell
        // which engine is talking even when sub-processes share a
        // session-level title.
        String head = msg.getProcessTitle() != null && !msg.getProcessTitle().isBlank()
                ? msg.getProcessTitle()
                : (msg.getProcessName() == null ? "?" : msg.getProcessName());
        return head + "(" + (msg.getEngine() == null ? "?" : msg.getEngine()) + ")";
    }

    private static String formatTokens(long n) {
        if (n < 1_000) return Long.toString(n);
        if (n < 1_000_000) return String.format("%.1fk", n / 1_000.0);
        return String.format("%.1fM", n / 1_000_000.0);
    }

    private static void countByStatus(PlanNode node, Map<String, Integer> acc) {
        if (node == null) return;
        String status = node.getStatus() == null ? "?" : node.getStatus();
        acc.merge(status, 1, Integer::sum);
        if (node.getChildren() != null) {
            for (PlanNode child : node.getChildren()) {
                countByStatus(child, acc);
            }
        }
    }
}
