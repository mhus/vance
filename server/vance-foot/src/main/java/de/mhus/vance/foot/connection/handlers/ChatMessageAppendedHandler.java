package de.mhus.vance.foot.connection.handlers;

import de.mhus.vance.api.chat.ChatMessageAppendedData;
import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.foot.audit.ConversationAuditService;
import de.mhus.vance.foot.chat.PendingAskUserPicker;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.connection.MessageHandler;
import de.mhus.vance.foot.session.SessionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.ColorResolver;
import de.mhus.vance.foot.ui.FollowUpSuggestionService;
import de.mhus.vance.foot.ui.StreamingDisplay;
import de.mhus.vance.foot.ui.ThinkingVisibility;
import de.mhus.vance.foot.ui.Verbosity;
import java.util.List;
import java.util.Objects;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(ChatMessageAppendedHandler.class);

    /** Configurable thoughts style, resolved from vance.ui.colors.thoughts. */
    private final @Nullable AttributedStyle thoughtsStyle;

    private final ChatTerminal terminal;
    private final StreamingDisplay streaming;
    private final SessionService sessions;
    private final PendingAskUserPicker askUserPicker;
    private final ConversationAuditService audit;
    private final FootConfig config;
    private final ThinkingVisibility thinkingVisibility;
    private final FollowUpSuggestionService followUpService;
    private final ObjectMapper json = JsonMapper.builder().build();

    public ChatMessageAppendedHandler(ChatTerminal terminal,
                                      StreamingDisplay streaming,
                                      SessionService sessions,
                                      PendingAskUserPicker askUserPicker,
                                      ConversationAuditService audit,
                                      FootConfig config,
                                      ThinkingVisibility thinkingVisibility,
                                      FollowUpSuggestionService followUpService,
                                      ColorResolver colorResolver) {
        this.terminal = terminal;
        this.streaming = streaming;
        this.sessions = sessions;
        this.askUserPicker = askUserPicker;
        this.audit = audit;
        this.config = config;
        this.thinkingVisibility = thinkingVisibility;
        this.followUpService = followUpService;
        this.thoughtsStyle = colorResolver.thoughts();
    }

    @Override
    public String messageType() {
        return MessageType.CHAT_MESSAGE_APPENDED;
    }

    @Override
    public void handle(WebSocketEnvelope envelope) {
        ChatMessageAppendedData data = json.convertValue(
                envelope.getData(), ChatMessageAppendedData.class);
        log.trace("handle: chat-message-appended — role={}, process={}, thinkProcessId={}, activeProcess={}, contentLen={}",
                data.getRole(),
                data.getProcessName(),
                data.getThinkProcessId(),
                sessions.activeProcess(),
                data.getContent() == null ? 0 : data.getContent().length());
        // Persist the message to the conversation audit log before
        // rendering — the audit is best-effort and must never block
        // the UI. Only ASSISTANT turns are audited here: USER turns
        // are captured locally at send time (see ChatInputService),
        // because the server does not echo a chat-message-appended for
        // them in solo sessions. Auditing USER echoes here too would
        // both miss them in solo sessions and duplicate them in collab
        // sessions where the echo does come back. SYSTEM notes are
        // ephemeral and not part of the conversation either way.
        if (data.getRole() == ChatRole.ASSISTANT) {
            audit.append(data);
        }
        // Close out the live reasoning stream. When reasoning was
        // streamed live this turn, suppress the end-of-turn thoughts
        // block below — the user already watched it tick in.
        boolean thinkingStreamed = streaming.commitThinking(data.getThinkProcessId());
        boolean wasStreamed = streaming.onCommit(data.getThinkProcessId());
        if (wasStreamed) {
            // Streamed turns already showed the answer live; thinking is
            // only carried on this canonical commit, so it lands right
            // after the reply (unless it was already streamed live).
            if (!thinkingStreamed) {
                maybeRenderThoughts(data);
            }
            // Any committed main-chat turn can change a shared-chat reply
            // suggestion; roles need not alternate.
            if (isMainProcess(data.getProcessName())) {
                followUpService.onConversationChanged();
            }
            maybeUpdatePicker(data);
            return;
        }
        String role = data.getRole() == null ? "?" : data.getRole().name().toLowerCase();
        // Multi-user header: USER turns that carry a senderDisplayName
        // surface the speaker's name in place of the bare "user" tag.
        // Solo sessions also see this (the speaker IS the owner) — a
        // tiny upgrade from the generic `[chat · user]` to a personal
        // tag. See planning/multi-user-sessions.md §6.
        String speaker = role;
        if (data.getRole() == ChatRole.USER
                && data.getSenderDisplayName() != null
                && !data.getSenderDisplayName().isBlank()) {
            speaker = data.getSenderDisplayName();
        }
        String header = "[" + data.getProcessName() + " · " + speaker + "] ";
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
        if (!thinkingStreamed) {
            maybeRenderThoughts(data);
        }
        if (isMainProcess(data.getProcessName())) {
            terminal.chatMarkdown(header, content);
        } else {
            terminal.worker(header + content);
        }
        if (isMainProcess(data.getProcessName())) {
            followUpService.onConversationChanged();
        }
        maybeUpdatePicker(data);
    }

    /**
     * Renders the assistant's reasoning ("thoughts") as a dimmed,
     * gutter-prefixed block at INFO level so it shows by default. Only
     * for main-process ASSISTANT turns — worker reasoning would just
     * clutter the side-channel. No-op when the runtime toggle is off
     * (Ctrl+T) or there is no thinking.
     */
    private void maybeRenderThoughts(ChatMessageAppendedData data) {
        if (!thinkingVisibility.isShowing()) return;
        if (data.getRole() != ChatRole.ASSISTANT) return;
        if (!isMainProcess(data.getProcessName())) return;
        String thinking = data.getThinking();
        if (thinking == null || thinking.isBlank()) return;
        terminal.printlnStyled(Verbosity.INFO, dim("💭 thoughts"));
        for (String line : thinking.strip().split("\\R", -1)) {
            terminal.printlnStyled(Verbosity.INFO, dim("│ " + line));
        }
    }

    private AttributedString dim(String text) {
        return ColorResolver.styled(thoughtsStyle)
                .append(text)
                .toAttributedString();
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
        String active = sessions.activeProcess();
        boolean match = Objects.equals(processName, active);
        if (!match) {
            log.trace("isMainProcess: MISMATCH — processName='{}', activeProcess='{}'", processName, active);
        }
        return match;
    }
}
