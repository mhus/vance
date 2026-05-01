package de.mhus.vance.brain.inbox.handlers;

import de.mhus.vance.api.inbox.InboxItemStatus;
import de.mhus.vance.api.inbox.InboxListRequest;
import de.mhus.vance.api.inbox.InboxListResponse;
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
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * Lists inbox items for the bound user. Default returns
 * {@link InboxItemStatus#PENDING} only; client can pass
 * {@link InboxListRequest#getStatus()} to filter differently.
 */
@Component
@RequiredArgsConstructor
public class InboxListHandler implements WsHandler {

    private final ObjectMapper objectMapper;
    private final WebSocketSender sender;
    private final InboxItemService inboxService;
    private final RequestAuthority authority;

    @Override
    public String type() {
        return MessageType.INBOX_LIST;
    }

    @Override
    public void handle(ConnectionContext ctx, WebSocketSession wsSession, WebSocketEnvelope envelope)
            throws IOException {
        InboxListRequest request;
        try {
            request = envelope.getData() == null
                    ? new InboxListRequest()
                    : objectMapper.convertValue(envelope.getData(), InboxListRequest.class);
        } catch (IllegalArgumentException e) {
            sender.sendError(wsSession, envelope, 400, "Invalid inbox-list payload: " + e.getMessage());
            return;
        }
        authority.enforce(ctx, new Resource.Tenant(ctx.getTenantId()), Action.READ);
        InboxItemStatus filter = request.getStatus() == null
                ? InboxItemStatus.PENDING : request.getStatus();
        List<InboxItemDocument> docs = inboxService.listForUser(
                ctx.getTenantId(), ctx.getUserId(), filter);
        InboxListResponse response = InboxListResponse.builder()
                .items(InboxMapper.toDtos(docs))
                .count(docs.size())
                .build();
        sender.sendReply(wsSession, envelope, MessageType.INBOX_LIST, response);
    }
}
