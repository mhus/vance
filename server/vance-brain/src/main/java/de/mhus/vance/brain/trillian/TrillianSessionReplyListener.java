package de.mhus.vance.brain.trillian;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.SteerMessageCodec;
import de.mhus.vance.brain.trillian.nature.CollabMode;
import de.mhus.vance.shared.chat.ChatMessageAppendedEvent;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The flow-back of A6 ("Mitarbeit fließt zurück"): whatever is said in a
 * session a Trillian opened — an engine answer or a human joining the work —
 * reaches the loop as a {@code <session-reply>} event, so a shared session
 * is a conversation and not a monologue.
 *
 * <p><b>Only verified own sessions.</b> The marker in the client name is a
 * claim anybody can make at the WS handshake; the session's owner has to be
 * the account it names ({@link TrillianOwnSessions#verifiedMarker}).
 *
 * <p><b>Collab-gated by the mode chosen at open time.</b>
 * {@link CollabMode#JOIN} and {@code SOLO} flow back; {@code WATCH} sessions
 * stay visible without disturbing the loop.
 *
 * <p><b>Only the conversation, never the echo.</b> Forwarded are the chat
 * process's final answers and lines a <em>human</em> wrote — not interim
 * working notes, not lines of worker processes inside that session, not the
 * Trillian's own messages (and nothing without a sender: a line nobody
 * claims cannot be told apart from the Trillian's own input).
 *
 * <p><b>To the loop that is alive now.</b> The loop session is fluid (D1); the
 * target is resolved from the account at delivery time, so a rebuilt loop
 * keeps hearing its sessions. Delivery goes through the engine-message
 * router; several lines arriving before the loop runs are drained together
 * in one turn.
 *
 * <p>Cheap by contract: this runs synchronously on the appending engine's
 * lane. The role filter comes before any lookup, so the common case (a
 * message in an ordinary session) costs one session read.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TrillianSessionReplyListener {

    /** The command a woken loop finds when a session it holds answered. */
    public static final String COMMAND_SESSION_REPLY = "trillian_session_reply";

    /** ExternalCommand params. */
    public static final String PARAM_SESSION_ID = "sessionId";

    public static final String PARAM_PREVIEW = "preview";

    /** ExternalCommand param: who said it — the engine, or a human's login. */
    public static final String PARAM_FROM = "from";

    private static final int PREVIEW_LIMIT = 240;

    private final SessionService sessionService;
    private final TrillianOwnSessions ownSessions;
    private final EngineMessageRouter messageRouter;

    @EventListener
    public void onChatMessageAppended(ChatMessageAppendedEvent event) {
        ChatMessageDocument message = event.message();
        if ((message.getRole() != ChatRole.ASSISTANT && message.getRole() != ChatRole.USER)
                || message.isInterim()
                || message.isRemoved()) {
            return;
        }
        try {
            SessionDocument session =
                    sessionService.findBySessionId(message.getSessionId()).orElse(null);
            if (session == null) {
                return;
            }
            TrillianOwnSessions.Marker marker =
                    TrillianOwnSessions.verifiedMarker(session).orElse(null);
            if (marker == null || marker.mode() == CollabMode.WATCH) {
                return;
            }
            String from = forwardableSender(message, session, marker.account());
            if (from == null) {
                return;
            }
            ThinkProcessDocument loop = ownSessions
                    .liveLoopOf(session.getTenantId(), marker.account())
                    .orElse(null);
            if (loop == null || loop.getId() == null) {
                log.trace("Trillian: no live loop for '{}' — session reply dropped", marker.account());
                return;
            }
            SteerMessage.ExternalCommand reply = new SteerMessage.ExternalCommand(
                    Instant.now(),
                    /*idempotencyKey*/ "session-reply-" + message.getId(),
                    COMMAND_SESSION_REPLY,
                    Map.of(
                            PARAM_SESSION_ID, session.getSessionId(),
                            PARAM_FROM, from,
                            PARAM_PREVIEW, preview(message.getContent())));
            messageRouter.dispatch(/*sender*/ null, loop.getId(), SteerMessageCodec.toDocument(reply));
        } catch (RuntimeException e) {
            log.warn("Trillian: session-reply flow-back failed: {}", e.toString());
        }
    }

    /**
     * Who said it, when it is worth forwarding: {@code "engine"} for the chat
     * process's answer, the human's login for a human's line; {@code null}
     * for everything else (worker lines, the Trillian's own input, unclaimed
     * lines).
     */
    static @Nullable String forwardableSender(ChatMessageDocument message, SessionDocument session, String account) {
        if (session.getChatProcessId() == null || !session.getChatProcessId().equals(message.getThinkProcessId())) {
            return null;
        }
        if (message.getRole() == ChatRole.ASSISTANT) {
            return "engine";
        }
        String sender = message.getSenderUserId();
        return sender == null || sender.equals(account) ? null : sender;
    }

    private static String preview(@Nullable String content) {
        String flat = content == null ? "" : content.strip();
        int nl = flat.indexOf('\n');
        String line = nl < 0 ? flat : flat.substring(0, nl);
        return line.length() <= PREVIEW_LIMIT ? line : line.substring(0, PREVIEW_LIMIT) + "…";
    }
}
