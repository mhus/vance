package de.mhus.vance.brain.events;

import de.mhus.vance.api.chat.ChatMessageAppendedData;
import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.shared.chat.ChatMessageAppendedEvent;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.Collection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * Pushes a {@link MessageType#CHAT_MESSAGE_APPENDED} frame to every
 * client currently bound to the message's session whenever
 * {@link ChatMessageAppendedEvent} fires — which is on <em>every</em>
 * {@code ChatMessageService.append}, no matter who triggered it. That
 * covers the Auto-Wakeup path (engine woke from a worker's
 * {@code ProcessEvent}) which previously wrote silently to Mongo
 * without telling the client.
 *
 * <p>USER-role behaviour depends on whether the session has multiple
 * connections (collaboration mode — see
 * {@code planning/multi-user-sessions.md} §3.5):
 *
 * <ul>
 *   <li><b>Single connection</b> (1:1 legacy session): skip the echo
 *       — the typing client already rendered the message optimistically.</li>
 *   <li><b>Multiple connections</b> (collab session): broadcast to
 *       <em>every</em> connection. The sender's client filters its
 *       own echo by comparing {@code senderUserId} to the local
 *       {@code userId} so the optimistic render isn't duplicated.</li>
 * </ul>
 *
 * <p>If no client is currently bound to the message's session, the
 * dispatch is a silent no-op — the message is in chat history and
 * will be replayed on the next session-resume.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChatMessageNotificationDispatcher {

    private final SessionConnectionRegistry connectionRegistry;
    private final ThinkProcessService thinkProcessService;
    private final WebSocketSender sender;

    @EventListener
    public void onChatMessageAppended(ChatMessageAppendedEvent event) {
        ChatMessageDocument msg = event.message();
        Collection<WebSocketSession> targets = connectionRegistry.findAll(msg.getSessionId());
        if (targets.isEmpty()) {
            // Session is bound to no live connection (suspended /
            // autonomous / pre-resume). Persistence is enough; the
            // client will fetch history on next bind.
            return;
        }
        if (msg.getRole() == ChatRole.USER && targets.size() == 1) {
            // Solo session: the only connected client just typed this
            // turn and already rendered it. No echo needed.
            return;
        }
        String processName = thinkProcessService.findById(msg.getThinkProcessId())
                .map(ThinkProcessDocument::getName)
                .orElse(null);
        ChatMessageAppendedData frame = ChatMessageAppendedData.builder()
                .chatMessageId(msg.getId())
                .thinkProcessId(msg.getThinkProcessId())
                .processName(processName)
                .role(msg.getRole())
                .content(msg.getContent())
                .createdAt(msg.getCreatedAt())
                .meta(msg.getMeta() == null || msg.getMeta().isEmpty()
                        ? null : msg.getMeta())
                .senderUserId(msg.getSenderUserId())
                .senderDisplayName(msg.getSenderDisplayName())
                .addressedToAgent(msg.isAddressedToAgent())
                .build();
        for (WebSocketSession ws : targets) {
            try {
                sender.sendNotification(ws, MessageType.CHAT_MESSAGE_APPENDED, frame);
            } catch (Exception e) {
                log.warn("Failed to push CHAT_MESSAGE_APPENDED for chatMessageId='{}' "
                                + "session='{}' ws='{}': {}",
                        msg.getId(), msg.getSessionId(), ws.getId(), e.toString());
            }
        }
    }
}
