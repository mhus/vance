package de.mhus.vance.brain.thinkengine;

import de.mhus.vance.api.inbox.AnswerOutcome;
import de.mhus.vance.api.inbox.AnswerPayload;
import de.mhus.vance.api.inbox.InboxItemType;
import de.mhus.vance.api.thinkprocess.PeerEventType;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.api.thinkprocess.ToolCallStatus;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.PendingMessageType;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bidirectional translation between the engine-side sealed
 * {@link SteerMessage} hierarchy and the persistent
 * {@link PendingMessageDocument}.
 *
 * <p>The split exists because {@code vance-shared} (where the
 * Mongo doc lives) cannot depend on {@code vance-brain} (where the
 * sealed type lives). Engines see only {@code SteerMessage}; the
 * brain runtime translates at the boundary.
 *
 * <p>Translation is total — every {@code SteerMessage} variant has
 * a persistent representation and vice versa. Unknown
 * {@link PendingMessageType} values throw {@link IllegalStateException}
 * so a corrupt row surfaces immediately rather than being silently
 * dropped.
 */
public final class SteerMessageCodec {

    private SteerMessageCodec() {}

    // ────────────────── encode (engine → mongo) ──────────────────

    public static PendingMessageDocument toDocument(SteerMessage msg) {
        PendingMessageDocument.PendingMessageDocumentBuilder b =
                PendingMessageDocument.builder()
                        .at(msg.at())
                        .idempotencyKey(msg.idempotencyKey());

        return switch (msg) {
            case SteerMessage.UserChatInput u -> b
                    .type(PendingMessageType.USER_CHAT_INPUT)
                    .fromUser(u.fromUser())
                    .content(u.content())
                    .build();

            case SteerMessage.ProcessEvent e -> b
                    .type(PendingMessageType.PROCESS_EVENT)
                    .sourceProcessId(e.sourceProcessId())
                    .eventType(e.type())
                    .content(e.humanSummary())
                    .payload(e.payload())
                    .build();

            case SteerMessage.ToolResult r -> b
                    .type(PendingMessageType.TOOL_RESULT)
                    .toolCallId(r.toolCallId())
                    .toolName(r.toolName())
                    .toolStatus(r.status())
                    .error(r.error())
                    .payload(wrapResult(r.result()))
                    .build();

            case SteerMessage.ExternalCommand c -> b
                    .type(PendingMessageType.EXTERNAL_COMMAND)
                    .command(c.command())
                    .payload(c.params())
                    .build();

            case SteerMessage.InboxAnswer ia -> b
                    .type(PendingMessageType.INBOX_ANSWER)
                    .inboxItemId(ia.inboxItemId())
                    .inboxItemType(ia.itemType())
                    .inboxAnswer(ia.answer())
                    .build();

            case SteerMessage.PeerEvent pe -> b
                    .type(PendingMessageType.PEER_EVENT)
                    .sourceVanceProcessId(pe.sourceVanceProcessId())
                    .peerUserId(pe.userId())
                    .peerEventType(pe.type())
                    .content(pe.humanSummary())
                    .payload(pe.payload())
                    .build();
        };
    }

    // ────────────────── decode (mongo → engine) ──────────────────

    public static SteerMessage toMessage(PendingMessageDocument d) {
        Instant at = d.getAt() == null ? Instant.EPOCH : d.getAt();
        String idem = d.getIdempotencyKey();
        PendingMessageType t = d.getType();
        if (t == null) {
            throw new IllegalStateException("PendingMessageDocument has null type");
        }
        return switch (t) {
            case USER_CHAT_INPUT -> new SteerMessage.UserChatInput(
                    at, idem,
                    nullToEmpty(d.getFromUser()),
                    nullToEmpty(d.getContent()));

            case PROCESS_EVENT -> new SteerMessage.ProcessEvent(
                    at, idem,
                    nullToEmpty(d.getSourceProcessId()),
                    d.getEventType() == null ? ProcessEventType.SUMMARY : d.getEventType(),
                    d.getContent(),
                    d.getPayload());

            case TOOL_RESULT -> new SteerMessage.ToolResult(
                    at, idem,
                    nullToEmpty(d.getToolCallId()),
                    nullToEmpty(d.getToolName()),
                    d.getToolStatus() == null ? ToolCallStatus.SUCCESS : d.getToolStatus(),
                    unwrapResult(d.getPayload()),
                    d.getError());

            case EXTERNAL_COMMAND -> new SteerMessage.ExternalCommand(
                    at, idem,
                    nullToEmpty(d.getCommand()),
                    d.getPayload() == null ? Collections.emptyMap() : d.getPayload());

            case INBOX_ANSWER -> new SteerMessage.InboxAnswer(
                    at, idem,
                    nullToEmpty(d.getInboxItemId()),
                    d.getInboxItemType() == null
                            ? InboxItemType.OUTPUT_TEXT : d.getInboxItemType(),
                    d.getInboxAnswer() == null
                            ? AnswerPayload.builder()
                                    .outcome(AnswerOutcome.UNDECIDABLE)
                                    .reason("Persistent inbox answer was missing")
                                    .build()
                            : d.getInboxAnswer());

            case PEER_EVENT -> new SteerMessage.PeerEvent(
                    at, idem,
                    nullToEmpty(d.getSourceVanceProcessId()),
                    nullToEmpty(d.getPeerUserId()),
                    d.getPeerEventType() == null ? PeerEventType.NOTE : d.getPeerEventType(),
                    nullToEmpty(d.getContent()),
                    d.getPayload());
        };
    }

    public static List<SteerMessage> toMessages(List<PendingMessageDocument> docs) {
        return docs.stream().map(SteerMessageCodec::toMessage).toList();
    }

    // ────────────────── ToolResult.result wrapper ──────────────────
    // The persistent {@code payload} field is a {@code Map<String,Object>};
    // tool results may be any JSON-shaped object. We box non-map results
    // under the key {@code "value"} on the way in and unbox on the way out.

    private static final String TOOL_RESULT_BOX_KEY = "value";

    private static Map<String, Object> wrapResult(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Map<?, ?> m) {
            Map<String, Object> typed = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : m.entrySet()) {
                typed.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return typed;
        }
        Map<String, Object> wrapped = new HashMap<>();
        wrapped.put(TOOL_RESULT_BOX_KEY, raw);
        return wrapped;
    }

    private static Object unwrapResult(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        if (payload.size() == 1 && payload.containsKey(TOOL_RESULT_BOX_KEY)) {
            return payload.get(TOOL_RESULT_BOX_KEY);
        }
        return payload;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
