package de.mhus.vance.foot.connection.handlers;

import de.mhus.vance.api.chat.ChatMessageAppendedData;
import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.foot.chat.PendingAskUserPicker;
import de.mhus.vance.foot.connection.MessageHandler;
import de.mhus.vance.foot.session.SessionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.StreamingDisplay;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Renders {@code chat-message-appended} notifications — the canonical
 * commit of a chat turn.
 *
 * <p>If the matching turn was streamed via
 * {@code chat-message-stream-chunk}, {@link StreamingDisplay#onCommit}
 * closes the streaming line with a newline and reports {@code true};
 * we then suppress the canonical render to avoid showing the same
 * text twice. For messages that didn't stream (user input echoes,
 * system notes, clients that don't support streaming) the canonical
 * is rendered as before.
 */
@Component
public class ChatMessageAppendedHandler implements MessageHandler {

    private final ChatTerminal terminal;
    private final StreamingDisplay streaming;
    private final SessionService sessions;
    private final PendingAskUserPicker askUserPicker;
    private final ObjectMapper json = JsonMapper.builder().build();

    public ChatMessageAppendedHandler(ChatTerminal terminal,
                                      StreamingDisplay streaming,
                                      SessionService sessions,
                                      PendingAskUserPicker askUserPicker) {
        this.terminal = terminal;
        this.streaming = streaming;
        this.sessions = sessions;
        this.askUserPicker = askUserPicker;
    }

    @Override
    public String messageType() {
        return MessageType.CHAT_MESSAGE_APPENDED;
    }

    @Override
    public void handle(WebSocketEnvelope envelope) {
        ChatMessageAppendedData data = json.convertValue(
                envelope.getData(), ChatMessageAppendedData.class);
        boolean wasStreamed = streaming.onCommit(data.getThinkProcessId());
        if (wasStreamed) {
            maybeUpdatePicker(data);
            return;
        }
        String role = data.getRole() == null ? "?" : data.getRole().name().toLowerCase();
        String header = "[" + data.getProcessName() + " · " + role + "] ";
        String content = data.getContent() == null ? "" : data.getContent();

        // Only the bound main process (Arthur) writes into the main
        // chat — that's the user-facing conversation. Workers run
        // under the hood; their chat-messages are audit material
        // that the orchestrator reads via {@code <process-event>}
        // markers and surfaces via {@code RELAY}. Showing them in
        // the main chat leads to dual-voice confusion ("[arthur]
        // ..." right next to "[web-research-xxx] ...") and
        // double-rendering with a subsequent RELAY. Worker rows go
        // to the dimmed side-channel as a transparent audit trail —
        // the user can see what the workers are doing, but not as
        // primary conversation content.
        if (isMainProcess(data.getProcessName())) {
            terminal.chatMarkdown(header, content);
        } else {
            terminal.worker(header + content);
        }
        maybeUpdatePicker(data);
    }

    /**
     * Maintains the active {@link PendingAskUserPicker} state in
     * lock-step with the incoming chat stream:
     * <ul>
     *   <li>USER message → clear; the user has answered (or asked
     *       something fresh) and any older picker is now stale.</li>
     *   <li>ASSISTANT message from the main process with non-empty
     *       {@code meta.askUserOptions} → present the new picker
     *       and render the numbered hint line right under the
     *       message.</li>
     *   <li>ASSISTANT message without options → leave the existing
     *       picker untouched (it might still apply to a question
     *       this turn doesn't address).</li>
     * </ul>
     */
    private void maybeUpdatePicker(ChatMessageAppendedData data) {
        if (data.getRole() == ChatRole.USER) {
            askUserPicker.clear();
            return;
        }
        if (data.getRole() != ChatRole.ASSISTANT) return;
        if (!isMainProcess(data.getProcessName())) return;
        List<PendingAskUserPicker.Option> opts =
                PendingAskUserPicker.parseOptions(data.getMeta());
        if (opts.isEmpty()) return;
        askUserPicker.present(opts);
        renderPickerHint(opts);
    }

    /**
     * Renders a one-line "[1] Label  [2] Label  …  (Zahl tippen oder
     * frei antworten)" hint under the question so the user knows the
     * numeric shortcut is available. The label is the spoken-friendly
     * form; descriptions are skipped here to keep the hint compact.
     */
    private void renderPickerHint(List<PendingAskUserPicker.Option> opts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < opts.size(); i++) {
            if (i > 0) sb.append("  ");
            sb.append('[').append(i + 1).append("] ").append(opts.get(i).label());
        }
        sb.append("   (Zahl tippen oder frei antworten)");
        terminal.chat(sb.toString());
    }

    private boolean isMainProcess(@org.jspecify.annotations.Nullable String processName) {
        if (processName == null) return false;
        return Objects.equals(processName, sessions.activeProcess());
    }
}
