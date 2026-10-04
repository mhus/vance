package de.mhus.vance.brain.trillian;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.SteerMessageCodec;
import de.mhus.vance.brain.trillian.nature.CollabMode;
import de.mhus.vance.brain.trillian.nature.TrillianNature;
import de.mhus.vance.brain.trillian.nature.TrillianNatureRegistry;
import de.mhus.vance.brain.trillian.tools.SessionOpenTool;
import de.mhus.vance.shared.chat.ChatMessageAppendedEvent;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The flow-back of A6 ("Mitarbeit fließt zurück"): whatever is said in a
 * session a Trillian opened — an engine answer or a human joining the work —
 * reaches the loop as a {@code <session-reply>} event, so a shared session
 * is a conversation and not a monologue.
 *
 * <p><b>Collab-gated.</b> Only {@link CollabMode#JOIN} flows back;
 * {@code WATCH} sessions stay visible without disturbing the loop — the
 * noise level is the Nature's decision, not the model's.
 *
 * <p>Cheap by contract: this runs synchronously on the appending engine's
 * lane, so it looks up two documents and queues one message. The turn it
 * may cause is scheduled, not run.
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

    private static final int PREVIEW_LIMIT = 240;

    private final SessionService sessionService;
    private final ThinkProcessService thinkProcessService;
    private final ProcessEventEmitter eventEmitter;
    private final TrillianNatureRegistry natureRegistry;

    @EventListener
    public void onChatMessageAppended(ChatMessageAppendedEvent event) {
        ChatMessageDocument message = event.message();
        try {
            SessionDocument session =
                    sessionService.findBySessionId(message.getSessionId()).orElse(null);
            if (session == null) {
                return;
            }
            String clientName = session.getClientName() == null ? "" : session.getClientName();
            if (!clientName.startsWith(SessionOpenTool.CLIENT_NAME_PREFIX)) {
                return;
            }
            String loopId = clientName.substring(SessionOpenTool.CLIENT_NAME_PREFIX.length());
            if (!worthForwarding(message, session)) {
                return;
            }
            ThinkProcessDocument loop = thinkProcessService.findById(loopId).orElse(null);
            if (loop == null || !joinMode(loop, session)) {
                return;
            }
            SteerMessage.ExternalCommand reply = new SteerMessage.ExternalCommand(
                    Instant.now(),
                    /*idempotencyKey*/ "session-reply-" + message.getId(),
                    COMMAND_SESSION_REPLY,
                    Map.of(
                            PARAM_SESSION_ID, session.getSessionId(),
                            PARAM_PREVIEW, preview(message.getContent())));
            if (thinkProcessService.appendPending(loopId, SteerMessageCodec.toDocument(reply))) {
                eventEmitter.scheduleTurn(loopId);
            }
        } catch (RuntimeException e) {
            log.warn("Trillian: session-reply flow-back failed: {}", e.toString());
        }
    }

    /**
     * Engine answers always matter; a chat line matters when it is not the
     * Trillian's own — its own sends come back through the same append path
     * and would echo into its inbox.
     */
    private static boolean worthForwarding(ChatMessageDocument message, SessionDocument session) {
        if (message.getRole() == ChatRole.ASSISTANT) {
            return true;
        }
        if (message.getRole() != ChatRole.USER) {
            return false;
        }
        String sender = message.getSenderUserId();
        return sender == null || !sender.equals(session.getUserId());
    }

    private boolean joinMode(ThinkProcessDocument loop, SessionDocument session) {
        try {
            TrillianNature nature = natureRegistry.resolve(TrillianSessionBootstrapper.readNature(loop));
            return nature.sessionCollab(loop, session.getProjectId(), null) == CollabMode.JOIN;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String preview(String content) {
        String flat = content == null ? "" : content.strip();
        int nl = flat.indexOf('\n');
        String line = nl < 0 ? flat : flat.substring(0, nl);
        return line.length() <= PREVIEW_LIMIT ? line : line.substring(0, PREVIEW_LIMIT) + "…";
    }
}
