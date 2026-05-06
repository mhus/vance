package de.mhus.vance.foot.connection.handlers;

import de.mhus.vance.api.thinkprocess.ProcessModeChangedNotification;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.foot.connection.MessageHandler;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.PlanModeState;
import de.mhus.vance.foot.ui.StreamingDisplay;
import de.mhus.vance.foot.ui.Verbosity;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Renders {@code process-mode-changed} notifications. Arthur emits this
 * when its {@code process.mode} transitions (NORMAL → EXPLORING via
 * {@code START_PLAN}, EXPLORING → PLANNING via {@code PROPOSE_PLAN},
 * PLANNING → EXECUTING via {@code START_EXECUTION}, etc.).
 *
 * <p>v1 rendering: a one-line dim status hint in the scrollback. A
 * future TUI can lift this into a persistent indicator next to the
 * prompt — see {@code readme/arthur-plan-mode.md} §10.
 */
@Component
public class ProcessModeChangedHandler implements MessageHandler {

    private static final AttributedStyle DIM_STYLE = AttributedStyle.DEFAULT
            .foreground(AttributedStyle.BRIGHT + AttributedStyle.BLACK);

    private final ChatTerminal terminal;
    private final StreamingDisplay streaming;
    private final PlanModeState planMode;
    private final ObjectMapper json = JsonMapper.builder().build();

    public ProcessModeChangedHandler(ChatTerminal terminal,
                                     StreamingDisplay streaming,
                                     PlanModeState planMode) {
        this.terminal = terminal;
        this.streaming = streaming;
        this.planMode = planMode;
    }

    @Override
    public String messageType() {
        return MessageType.PROCESS_MODE_CHANGED;
    }

    @Override
    public void handle(WebSocketEnvelope envelope) {
        ProcessModeChangedNotification msg = json.convertValue(
                envelope.getData(), ProcessModeChangedNotification.class);
        if (msg == null || msg.getNewMode() == null) return;

        // Don't render the no-op transitions (NORMAL ↔ NORMAL) — only
        // the actual mode-change lines.
        if (msg.getOldMode() == msg.getNewMode()) return;

        String name = msg.getProcessName() == null || msg.getProcessName().isBlank()
                ? "process"
                : msg.getProcessName();
        // State update first so the StatusBar repaint that lands inside
        // setMode() reflects the new mode (and clears todos when we hit
        // NORMAL) before the audit-trail line is written.
        planMode.setMode(name, msg.getNewMode());

        streaming.suspend();
        String text = String.format("[mode] %s · %s → %s",
                name, msg.getOldMode(), msg.getNewMode());
        terminal.printlnStyled(Verbosity.INFO, dim(text));
    }

    private static AttributedString dim(String text) {
        return new AttributedStringBuilder()
                .style(DIM_STYLE)
                .append(text)
                .toAttributedString();
    }
}
