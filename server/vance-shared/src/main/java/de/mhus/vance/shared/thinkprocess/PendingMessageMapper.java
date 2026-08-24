package de.mhus.vance.shared.thinkprocess;

import de.mhus.vance.shared.enginemessage.EngineMessageDocument;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The one translation between the {@link PendingMessageDocument} façade the
 * engine and tool layers pass around and the {@link EngineMessageDocument}
 * persistence form.
 *
 * <p><b>Why this class exists.</b> The mapping was written twice — once in
 * {@code ThinkProcessService} as the "legacy bridge", once in
 * {@code EngineMessageRouter} with a javadoc that said so
 * (<em>"Same field-mapping … duplicated here so the engine-to-engine call sites
 * can drop the shim"</em>). Two copies of a field list drift in exactly one
 * direction: a new field gets added to the copy whose call site needed it. That
 * happened twice on one day — {@code fromUserDisplayName} and
 * {@code activeInbox} were both added to the bridge and not to the router, and
 * neither omission was visible from the call site, because the field simply
 * arrives as {@code null} at the far end.
 *
 * <p>{@link EngineMessageDocument} is the durable hop between a sender and the
 * engine drain, on this pod or another one. <b>A field this mapper forgets is a
 * field the engine never sees</b>, and nothing reports it. That is the whole
 * reason to have one place.
 *
 * <p>Lives in {@code thinkprocess} rather than next to
 * {@code EngineMessageDocument}: this package already depends on
 * {@code enginemessage}, the reverse direction would be a new package cycle.
 */
public final class PendingMessageMapper {

    private PendingMessageMapper() {}

    /**
     * Pending → durable engine message.
     *
     * @param targetProcessId  the process this is being delivered to
     * @param tenantId         tenant of the target; {@code null} becomes {@code ""}
     * @param senderProcessId  emitter, or {@code null}/{@code ""} for "not a process"
     */
    public static EngineMessageDocument toEngineMessage(
            PendingMessageDocument m,
            String targetProcessId,
            @Nullable String tenantId,
            @Nullable String senderProcessId) {
        // The idempotency key is the message id when there is one — that is what
        // makes a cross-pod retry a no-op at the receiver.
        String messageId = (m.getIdempotencyKey() != null && !m.getIdempotencyKey().isBlank())
                ? m.getIdempotencyKey()
                : UUID.randomUUID().toString();
        Instant createdAt = m.getAt() == null || m.getAt().equals(Instant.EPOCH)
                ? Instant.now() : m.getAt();
        return EngineMessageDocument.builder()
                .messageId(messageId)
                .tenantId(tenantId == null ? "" : tenantId)
                .senderProcessId(senderProcessId == null ? "" : senderProcessId)
                .targetProcessId(targetProcessId)
                .createdAt(createdAt)
                .type(m.getType())
                // ─── USER_CHAT_INPUT and its per-turn view context ───
                .fromUser(m.getFromUser())
                .fromUserDisplayName(m.getFromUserDisplayName())
                .content(m.getContent())
                .voiceMode(m.getVoiceMode())
                .attachmentDocumentIds(m.getAttachmentDocumentIds())
                .activeApp(m.getActiveApp())
                .boundDocumentId(m.getBoundDocumentId())
                .boundDocSelection(m.getBoundDocSelection())
                .activeInbox(m.getActiveInbox())
                // ─── PROCESS_EVENT / TOOL_RESULT / commands / inbox ───
                .sourceProcessId(m.getSourceProcessId())
                .eventType(m.getEventType())
                .eventId(m.getEventId())
                .inResponseToAt(m.getInResponseToAt())
                .toolCallId(m.getToolCallId())
                .toolName(m.getToolName())
                .toolStatus(m.getToolStatus())
                .error(m.getError())
                .command(m.getCommand())
                .inboxItemId(m.getInboxItemId())
                .inboxItemType(m.getInboxItemType())
                .inboxAnswer(m.getInboxAnswer())
                .sourceEddieProcessId(m.getSourceEddieProcessId())
                .peerUserId(m.getPeerUserId())
                .peerEventType(m.getPeerEventType())
                .payload(m.getPayload())
                .build();
    }

    /** Durable engine message → pending, for the drain. */
    public static PendingMessageDocument toPendingMessage(EngineMessageDocument e) {
        return PendingMessageDocument.builder()
                .at(e.getCreatedAt())
                .idempotencyKey(e.getMessageId())
                .type(e.getType())
                .fromUser(e.getFromUser())
                .fromUserDisplayName(e.getFromUserDisplayName())
                .content(e.getContent())
                .voiceMode(e.getVoiceMode())
                .attachmentDocumentIds(e.getAttachmentDocumentIds())
                .activeApp(e.getActiveApp())
                .boundDocumentId(e.getBoundDocumentId())
                .boundDocSelection(e.getBoundDocSelection())
                .activeInbox(e.getActiveInbox())
                .sourceProcessId(e.getSourceProcessId())
                .eventType(e.getEventType())
                .eventId(e.getEventId())
                .inResponseToAt(e.getInResponseToAt())
                .toolCallId(e.getToolCallId())
                .toolName(e.getToolName())
                .toolStatus(e.getToolStatus())
                .error(e.getError())
                .command(e.getCommand())
                .inboxItemId(e.getInboxItemId())
                .inboxItemType(e.getInboxItemType())
                .inboxAnswer(e.getInboxAnswer())
                .sourceEddieProcessId(e.getSourceEddieProcessId())
                .peerUserId(e.getPeerUserId())
                .peerEventType(e.getPeerEventType())
                .payload(e.getPayload())
                .build();
    }
}
