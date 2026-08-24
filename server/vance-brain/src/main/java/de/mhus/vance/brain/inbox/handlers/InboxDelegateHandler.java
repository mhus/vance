package de.mhus.vance.brain.inbox.handlers;

import de.mhus.vance.api.inbox.InboxDelegateRequest;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.inbox.InboxMapper;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.brain.ws.WsHandler;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.inbox.MaximegalonService;
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
public class InboxDelegateHandler implements WsHandler {

    private final ObjectMapper objectMapper;
    private final WebSocketSender sender;
    private final MaximegalonService inboxService;
    private final RequestAuthority authority;

    @Override
    public String type() {
        return MessageType.INBOX_DELEGATE;
    }

    @Override
    public void handle(ConnectionContext ctx, WebSocketSession wsSession, WebSocketEnvelope envelope)
            throws IOException {
        InboxDelegateRequest request;
        try {
            request = objectMapper.convertValue(envelope.getData(), InboxDelegateRequest.class);
        } catch (IllegalArgumentException e) {
            sender.sendError(wsSession, envelope, 400,
                    "Invalid inbox-delegate payload: " + e.getMessage());
            return;
        }
        if (request == null || request.getItemId() == null || request.getItemId().isBlank()
                || request.getToUserId() == null || request.getToUserId().isBlank()) {
            sender.sendError(wsSession, envelope, 400, "itemId and toUserId are required");
            return;
        }
        Optional<MaximegalonDocument> existing =
                inboxService.findById(ctx.getTenantId(), request.getItemId());
        if (existing.isEmpty()) {
            sender.sendError(wsSession, envelope, 404, "Inbox item not found");
            return;
        }
        MaximegalonDocument item = existing.get();
        authority.enforce(ctx, new Resource.InboxItem(
                        item.getTenantId() == null ? "" : item.getTenantId(),
                        item.getId() == null ? "" : item.getId(),
                        item.getAssignedToUserId() == null ? "" : item.getAssignedToUserId()),
                Action.WRITE);
        // And WRITE on the *target's* inbox: the check above says the caller may
        // settle this thread, not that they may put it on that person's desk.
        // Mirrors the REST path and invite(); see InboxAuthz — change both or
        // neither.
        authority.enforce(ctx, new Resource.InboxItem(
                        ctx.getTenantId(), "", request.getToUserId()),
                Action.WRITE);

        Optional<MaximegalonDocument> updated = inboxService.delegate(
                ctx.getTenantId(), request.getItemId(),
                request.getToUserId(), ctx.getUserId(), request.getNote());
        if (updated.isEmpty()) {
            sender.sendError(wsSession, envelope, 404, "Inbox item not found");
            return;
        }
        sender.sendReply(wsSession, envelope, MessageType.INBOX_DELEGATE,
                InboxMapper.toDto(updated.get()));
    }
}
