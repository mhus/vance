package de.mhus.vance.brain.tools.eddie;

import de.mhus.vance.api.thinkprocess.PeerEventType;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.brain.eddie.EddieEngine;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.PendingMessageType;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Sends a {@code PeerEvent} to every other Eddie hub-process of the
 * same user — i.e. all parallel hub-sessions on the user's other
 * devices. Used when one hub does something that the others should
 * stay aware of (project created, worker dispatched, important user
 * statement). Each peer's lane is scheduled, so a suspended hub will
 * wake briefly, drain the event into its conversation context, and
 * suspend again — surfacing it the next time the user lands there.
 *
 * <p>Hub-only mechanic: regular worker engines (Arthur, Marvin)
 * don't emit or consume PeerEvents.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PeerNotifyTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "type", Map.of(
                            "type", "string",
                            "enum", List.of(
                                    "PROJECT_CREATED",
                                    "PROJECT_ARCHIVED",
                                    "PROCESS_SPAWNED",
                                    "PROCESS_STATUS_CHANGED",
                                    "USER_STATEMENT",
                                    "NOTE"),
                            "description", "Peer-event flavour."),
                    "summary", Map.of(
                            "type", "string",
                            "description", "One-line, voice-friendly summary "
                                    + "the peer hubs paste verbatim."),
                    "payload", Map.of(
                            "type", "object",
                            "description", "Optional structured side-channel "
                                    + "data — project name, process id, etc.",
                            "additionalProperties", true)),
            "required", List.of("type", "summary"));

    private final SessionService sessionService;
    private final ThinkProcessService thinkProcessService;
    private final ProcessEventEmitter eventEmitter;

    @Override
    public String name() {
        return "peer_notify";
    }

    @Override
    public String description() {
        return "Notify all other Eddie hub-sessions of the same user "
                + "about something this hub just did. Use sparingly — "
                + "for project/process state changes that the user "
                + "should see across devices, not for every chat turn.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.processId() == null) {
            throw new ToolException("peer_notify requires an Eddie process scope");
        }
        if (ctx.userId() == null) {
            throw new ToolException("peer_notify requires a user identity");
        }
        PeerEventType type = parseType(stringOrThrow(params, "type"));
        String summary = stringOrThrow(params, "summary");
        Map<String, Object> payload = optMap(params, "payload");

        List<String> peerIds = findPeerEddieProcessIds(ctx);
        int notified = 0;
        for (String peerId : peerIds) {
            PendingMessageDocument msg = PendingMessageDocument.builder()
                    .type(PendingMessageType.PEER_EVENT)
                    .at(Instant.now())
                    .sourceVanceProcessId(ctx.processId())
                    .peerUserId(ctx.userId())
                    .peerEventType(type)
                    .content(summary)
                    .payload(payload)
                    .build();
            if (thinkProcessService.appendPending(peerId, msg)) {
                eventEmitter.scheduleTurn(peerId);
                notified++;
            }
        }

        log.info("peer_notify: tenant='{}' user='{}' from='{}' type={} notified={}/{}",
                ctx.tenantId(), ctx.userId(), ctx.processId(),
                type, notified, peerIds.size());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type.name());
        out.put("peersNotified", notified);
        out.put("peersFound", peerIds.size());
        return out;
    }

    /**
     * Walks the user's sessions, picks the chat-process of each, and
     * keeps the ones that are running the {@code eddie} engine and
     * are not the calling process itself.
     */
    private List<String> findPeerEddieProcessIds(ToolInvocationContext ctx) {
        List<SessionDocument> sessions = sessionService.listForUser(
                ctx.tenantId(), ctx.userId());
        List<String> peers = new ArrayList<>();
        for (SessionDocument s : sessions) {
            String chatId = s.getChatProcessId();
            if (chatId == null || chatId.equals(ctx.processId())) continue;
            thinkProcessService.findById(chatId).ifPresent(p -> {
                if (EddieEngine.NAME.equals(p.getThinkEngine())) {
                    peers.add(p.getId());
                }
            });
        }
        return peers;
    }

    private static PeerEventType parseType(String raw) {
        try {
            return PeerEventType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ToolException("Unknown peer-event type '" + raw + "'");
        }
    }

    private static String stringOrThrow(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required and must be a non-empty string");
        }
        return s.trim();
    }

    private static Map<String, Object> optMap(Map<String, Object> params, String key) {
        if (params == null) return null;
        Object raw = params.get(key);
        if (raw instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        return null;
    }
}
