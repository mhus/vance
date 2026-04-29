package de.mhus.vance.foot.connection.handlers;

import de.mhus.vance.api.progress.MetricsPayload;
import de.mhus.vance.api.progress.PlanNode;
import de.mhus.vance.api.progress.PlanPayload;
import de.mhus.vance.api.progress.ProcessProgressNotification;
import de.mhus.vance.api.progress.StatusPayload;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.foot.connection.MessageHandler;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.Verbosity;
import java.util.HashMap;
import java.util.Map;
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
 * line for status. A future TUI overlay (split-screen with Lanterna)
 * can swap this implementation without touching the wire contract.
 */
@Component
public class ProcessProgressHandler implements MessageHandler {

    private final ChatTerminal terminal;
    private final ObjectMapper json = JsonMapper.builder().build();

    public ProcessProgressHandler(ChatTerminal terminal) {
        this.terminal = terminal;
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
        String src = formatSource(msg);
        switch (msg.getKind()) {
            case METRICS -> renderMetrics(src, msg.getMetrics());
            case PLAN -> renderPlan(src, msg.getPlan());
            case STATUS -> renderStatus(src, msg.getStatus());
        }
    }

    private void renderMetrics(String src, @Nullable MetricsPayload m) {
        if (m == null) return;
        // HUD-style one-liner. Verbosity VERBOSE so the user can mute
        // at INFO if the per-roundtrip cadence gets too chatty.
        terminal.println(Verbosity.VERBOSE,
                "[hud] %s · %d calls · %s in / %s out · %.1fs%s",
                src,
                m.getLlmCallCount(),
                formatTokens(m.getTokensInTotal()),
                formatTokens(m.getTokensOutTotal()),
                m.getElapsedMs() / 1000.0,
                m.getModelAlias() == null ? "" : " · " + m.getModelAlias());
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
        terminal.println(Verbosity.INFO, "%s", sb.toString());
    }

    private void renderStatus(String src, @Nullable StatusPayload s) {
        if (s == null) return;
        terminal.println(Verbosity.INFO,
                "[%s] %s · %s",
                s.getTag() == null ? "?" : s.getTag().name().toLowerCase(),
                src,
                s.getText() == null ? "" : s.getText());
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
