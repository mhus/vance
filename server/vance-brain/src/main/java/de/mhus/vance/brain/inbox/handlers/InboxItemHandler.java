package de.mhus.vance.brain.inbox.handlers;

import de.mhus.vance.api.inbox.InboxItemIdRequest;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.inbox.InboxMapper;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.brain.ws.WsHandler;
import de.mhus.vance.shared.inbox.InboxItemDocument;
import de.mhus.vance.shared.inbox.InboxItemService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import java.io.IOException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class InboxItemHandler implements WsHandler {

    private final ObjectMapper objectMapper;
    private final WebSocketSender sender;
    private final InboxItemService inboxService;
    private final RequestAuthority authority;

    @Override
    public String type() {
        return MessageType.INBOX_ITEM;
    }

    @Override
    public void handle(ConnectionContext ctx, WebSocketSession wsSession, WebSocketEnvelope envelope)
            throws IOException {
        InboxItemIdRequest request;
        try {
            request = objectMapper.convertValue(envelope.getData(), InboxItemIdRequest.class);
        } catch (IllegalArgumentException e) {
            sender.sendError(wsSession, envelope, 400, "Invalid inbox-item payload: " + e.getMessage());
            return;
        }
        if (request == null || request.getItemId() == null || request.getItemId().isBlank()) {
            sender.sendError(wsSession, envelope, 400, "itemId is required");
            return;
        }
        Optional<InboxItemDocument> doc = inboxService.findById(ctx.getTenantId(), request.getItemId());
        if (doc.isEmpty()) {
            sender.sendError(wsSession, envelope, 404, "Inbox item not found");
            return;
        }
        InboxItemDocument item = doc.get();
        authority.enforce(ctx, new Resource.InboxItem(
                        item.getTenantId() == null ? "" : item.getTenantId(),
                        item.getId() == null ? "" : item.getId(),
                        item.getAssignedToUserId() == null ? "" : item.getAssignedToUserId()),
                Action.READ);
        sender.sendReply(wsSession, envelope, MessageType.INBOX_ITEM, InboxMapper.toDto(item));
    }
}
