package de.mhus.vance.brain.inbox.handlers;

import de.mhus.vance.api.inbox.AnswerOutcome;
import de.mhus.vance.api.inbox.AnswerPayload;
import de.mhus.vance.api.inbox.InboxAnswerRequest;
import de.mhus.vance.api.inbox.ResolvedBy;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * Persists an inbox answer. The downstream routing back to the
 * originating process is handled by
 * {@code InboxAnsweredListener} (subscribes to
 * {@code MaximegalonAnsweredEvent}).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InboxAnswerHandler implements WsHandler {

    private final ObjectMapper objectMapper;
    private final WebSocketSender sender;
    private final MaximegalonService inboxService;
    private final RequestAuthority authority;

    @Override
    public String type() {
        return MessageType.INBOX_ANSWER;
    }

    @Override
    public void handle(ConnectionContext ctx, WebSocketSession wsSession, WebSocketEnvelope envelope)
            throws IOException {
        InboxAnswerRequest request;
        try {
            request = objectMapper.convertValue(envelope.getData(), InboxAnswerRequest.class);
        } catch (IllegalArgumentException e) {
            sender.sendError(wsSession, envelope, 400,
                    "Invalid inbox-answer payload: " + e.getMessage());
            return;
        }
        if (request == null || request.getItemId() == null || request.getItemId().isBlank()) {
            sender.sendError(wsSession, envelope, 400, "itemId is required");
            return;
        }
        AnswerOutcome outcome = request.getOutcome() == null
                ? AnswerOutcome.DECIDED : request.getOutcome();
        if (outcome == AnswerOutcome.DECIDED && request.getValue() == null) {
            sender.sendError(wsSession, envelope, 400,
                    "DECIDED outcome requires 'value'");
            return;
        }
        if ((outcome == AnswerOutcome.INSUFFICIENT_INFO
                || outcome == AnswerOutcome.UNDECIDABLE)
                && (request.getReason() == null || request.getReason().isBlank())) {
            sender.sendError(wsSession, envelope, 400,
                    outcome.name() + " outcome requires a 'reason'");
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

        AnswerPayload payload = AnswerPayload.builder()
                .outcome(outcome)
                .value(request.getValue())
                .reason(request.getReason())
                .answeredBy(ctx.getUserId())
                .build();
        Optional<MaximegalonDocument> updated = inboxService.answer(
                ctx.getTenantId(), request.getItemId(), payload, ResolvedBy.USER);
        if (updated.isEmpty()) {
            sender.sendError(wsSession, envelope, 404, "Inbox item not found");
            return;
        }
        sender.sendReply(wsSession, envelope, MessageType.INBOX_ANSWER,
                InboxMapper.toDto(updated.get()));
    }
}
