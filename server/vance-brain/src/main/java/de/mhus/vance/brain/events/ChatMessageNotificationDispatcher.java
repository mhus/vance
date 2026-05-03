package de.mhus.vance.brain.events;

import de.mhus.vance.api.chat.ChatMessageAppendedData;
import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.shared.chat.ChatMessageAppendedEvent;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * Pushes a {@link MessageType#CHAT_MESSAGE_APPENDED} frame to the
 * connected client whenever {@link ChatMessageAppendedEvent} fires —
 * which is on <em>every</em> {@code ChatMessageService.append}, no
 * matter who triggered it. That covers the Auto-Wakeup path (engine
 * woke from a worker's {@code ProcessEvent}) which previously wrote
 * silently to Mongo without telling the client.
 *
 * <p>USER-role messages are skipped: the client just sent them, no
 * point echoing them back.
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
        if (msg.getRole() == ChatRole.USER) {
            // Client just typed it — no echo.
            return;
        }
        WebSocketSession ws = connectionRegistry.find(msg.getSessionId()).orElse(null);
        if (ws == null) {
            // Session is bound to no live connection (suspended /
            // autonomous / pre-resume). Persistence is enough; the
            // client will fetch history on next bind.
            return;
        }
        String processName = thinkProcessService.findById(msg.getThinkProcessId())
                .map(ThinkProcessDocument::getName)
                .orElse(null);
        try {
            sender.sendNotification(ws, MessageType.CHAT_MESSAGE_APPENDED,
                    ChatMessageAppendedData.builder()
                            .chatMessageId(msg.getId())
                            .thinkProcessId(msg.getThinkProcessId())
                            .processName(processName)
                            .role(msg.getRole())
                            .content(msg.getContent())
                            .createdAt(msg.getCreatedAt())
                            .build());
        } catch (Exception e) {
            log.warn("Failed to push CHAT_MESSAGE_APPENDED for chatMessageId='{}' session='{}': {}",
                    msg.getId(), msg.getSessionId(), e.toString());
        }
    }
}
