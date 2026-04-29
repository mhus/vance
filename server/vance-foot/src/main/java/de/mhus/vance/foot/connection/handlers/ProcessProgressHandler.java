package de.mhus.vance.foot.connection.handlers;

import de.mhus.vance.api.progress.MetricsPayload;
import de.mhus.vance.api.progress.PlanNode;
import de.mhus.vance.api.progress.PlanPayload;
import de.mhus.vance.api.progress.ProcessProgressNotification;
import de.mhus.vance.api.progress.ProgressKind;
import de.mhus.vance.api.progress.StatusPayload;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.foot.connection.MessageHandler;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.StreamingDisplay;
import de.mhus.vance.foot.ui.Verbosity;
import java.util.HashMap;
import java.util.Map;
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
    private final ObjectMapper json = JsonMapper.builder().build();

    public ProcessProgressHandler(ChatTerminal terminal, StreamingDisplay streaming) {
        this.terminal = terminal;
        this.streaming = streaming;
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
        // Skip the whole pipeline (including the streaming-suspend
        // newline) when the user's verbosity threshold would filter
        // this kind out. Otherwise we'd emit a stray newline that
        // unhooks the in-flight chat stream from its onCommit and
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
        String line = String.format(
                "[%s] %s · %s",
                s.getTag() == null ? "?" : s.getTag().name().toLowerCase(),
                src,
                s.getText() == null ? "" : s.getText());
        terminal.printlnStyled(Verbosity.INFO, dim(line));
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
