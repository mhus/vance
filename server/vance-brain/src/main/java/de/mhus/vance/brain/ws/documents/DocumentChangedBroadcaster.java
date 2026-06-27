package de.mhus.vance.brain.ws.documents;

import de.mhus.vance.api.ws.DocumentChangedNotification;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.shared.document.DocumentLiveChangedEvent;
import de.mhus.vance.shared.redis.VanceRedisMessagingService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Bridges {@link DocumentLiveChangedEvent} (local Spring event fired by
 * {@code DocumentService}) and the {@code documents} WS channel.
 *
 * <p>Two paths:
 *
 * <ol>
 *   <li><b>Local-write fan-out:</b> the pod that performs the write
 *       receives a Spring event; we publish a Redis Pub/Sub signal on
 *       {@code vance:{tenant}:documents.changed} so peer pods learn
 *       about the change, and we immediately push a
 *       {@link MessageType#DOCUMENT_CHANGED} frame to our own local
 *       subscribers.</li>
 *   <li><b>Cross-pod fan-out:</b> every pod subscribes to the pattern
 *       {@code vance:*:documents.changed}; incoming messages get parsed,
 *       self-echoes are ignored, and the affected path is broadcast to
 *       local subscribers (if any).</li>
 * </ol>
 *
 * <p>Wire format on Redis: {@code "{podId}|{base64(path)}|{kind}"}
 * — same envelope shape as the presence channel for symmetry. {@code kind}
 * is the lower-cased enum name ({@code upserted} / {@code deleted}).
 *
 * <p>Deliberately separate from the brain-internal
 * {@code DocumentChangeRouter} / {@code /internal/document/changed}
 * dispatcher: that one targets the project-home pod for in-memory cache
 * coherence, which cannot reach WS subscribers hanging on other pods.
 */
@Service
@Slf4j
public class DocumentChangedBroadcaster {

    static final String CHANNEL = "documents.changed";

    private final DocumentSubscriberRegistry registry;
    private final VanceRedisMessagingService redis;
    private final WebSocketSender sender;

    /** Per-process identity used to ignore our own Redis pub/sub echoes. */
    private final String podId = UUID.randomUUID().toString();

    public DocumentChangedBroadcaster(
            DocumentSubscriberRegistry registry,
            VanceRedisMessagingService redis,
            WebSocketSender sender) {
        this.registry = registry;
        this.redis = redis;
        this.sender = sender;
    }

    @PostConstruct
    public void start() {
        redis.subscribeAcrossTenants(CHANNEL, this::onRemoteChanged);
        log.debug("DocumentChangedBroadcaster: podId={} redis.enabled={}",
                podId, redis.isEnabled());
    }

    @PreDestroy
    public void stop() {
        redis.unsubscribeAcrossTenants(CHANNEL);
    }

    /**
     * Spring event listener — fires on the pod that performed the write.
     * Publishes a Redis signal for peer pods and pushes the frame to
     * local subscribers in one go.
     */
    @EventListener
    public void onLocalChanged(DocumentLiveChangedEvent event) {
        String tenant = event.tenantId();
        String path = event.path();
        String kind = wireKind(event.kind());
        WriterMeta writer = new WriterMeta(
                event.editorId(), event.editorUserId(), event.editorDisplayName());
        log.trace("documents.changed[local] podId={} tenant={} path={} kind={} writer={} → publish + broadcast",
                podId, tenant, path, kind, writer);
        try {
            redis.publish(tenant, CHANNEL, encodePayload(path, kind, writer));
        } catch (RuntimeException ex) {
            log.warn("documents.changed[local] redis publish failed for '{}/{}': {}",
                    tenant, path, ex.toString());
        }
        broadcastLocal(path, kind, writer, "local");
    }

    private void onRemoteChanged(String topic, String body) {
        // Wire format (variable-length, all suffix fields optional):
        //   {podId}|{base64(path)}|{kind}[|{editorId}[|{base64(displayName)}[|{userId}]]]
        // Pods on older rolling-deploy code may still publish 3-part
        // frames; we accept anything ≥3 parts gracefully.
        String[] parts = body.split("\\|", -1);
        if (parts.length < 3) {
            log.debug("documents.changed[remote] malformed body on {}: '{}'", topic, body);
            return;
        }
        String senderPodId = parts[0];
        if (Objects.equals(senderPodId, podId)) {
            log.trace("documents.changed[remote] self-echo on {} ignored", topic);
            return;
        }
        String path = decodePath(parts[1]);
        if (path == null) {
            log.debug("documents.changed[remote] undecodable path on {}: '{}'", topic, parts[1]);
            return;
        }
        String kind = parts[2];
        String editorId = parts.length >= 4 && !parts[3].isEmpty() ? parts[3] : null;
        String displayName = parts.length >= 5 && !parts[4].isEmpty()
                ? decodePath(parts[4]) : null;
        String userId = parts.length >= 6 && !parts[5].isEmpty() ? parts[5] : null;
        WriterMeta writer = new WriterMeta(editorId, userId, displayName);
        log.trace("documents.changed[remote] from pod={} on topic={} path={} kind={} writer={}",
                senderPodId, topic, path, kind, writer);
        broadcastLocal(path, kind, writer, "remote");
    }

    private void broadcastLocal(String path, String kind, WriterMeta writer,
            String source) {
        if (!registry.hasLocalSubscribers(path)) {
            log.debug("documents.changed[{}] no local subscribers for path={} → drop", source, path);
            return;
        }
        DocumentChangedNotification payload = DocumentChangedNotification.builder()
                .path(path)
                .kind(kind)
                .editorId(writer.editorId())
                .editorUserId(writer.userId())
                .editorDisplayName(writer.displayName())
                .build();
        WebSocketEnvelope envelope = WebSocketEnvelope.notification(
                MessageType.DOCUMENT_CHANGED, payload);

        // Dedupe across the two recipient lanes: a session subscribed
        // both by exact-path AND by a matching prefix would otherwise
        // get the same change twice.
        Set<String> deliveredTo = new HashSet<>();
        int[] sent = {0};
        int[] skipped = {0};
        java.util.function.BiConsumer<org.springframework.web.socket.WebSocketSession,
                de.mhus.vance.brain.ws.ConnectionContext> deliver = (wsSession, ctx) -> {
            // Skip the writer's own connection — they just performed the
            // save themselves, they already have the freshest content.
            if (writer.editorId() != null
                    && Objects.equals(ctx.getEditorId(), writer.editorId())) {
                skipped[0]++;
                log.trace("documents.changed[{}] skip writer ws='{}' editorId='{}' path={}",
                        source, wsSession.getId(), ctx.getEditorId(), path);
                return;
            }
            if (!deliveredTo.add(wsSession.getId())) return;
            try {
                sender.sendOnChannel(wsSession, "documents", envelope);
                sent[0]++;
                log.trace("documents.changed[{}] → ws='{}' user='{}' editorId='{}' path={}",
                        source, wsSession.getId(), ctx.getUserId(), ctx.getEditorId(), path);
            } catch (IOException e) {
                log.debug("documents.changed[{}] push failed ws='{}' path='{}': {}",
                        source, wsSession.getId(), path, e.toString());
            }
        };
        registry.forEachLocalSubscriber(path, deliver);
        registry.forEachLocalPrefixSubscriber(path, deliver);
        log.trace("documents.changed[{}] fanOut path={} kind={} recipients={} skippedWriter={}",
                source, path, kind, sent[0], skipped[0]);
    }

    // ─── wire helpers ──────────────────────────────────────────────────

    private record WriterMeta(
            @Nullable String editorId,
            @Nullable String userId,
            @Nullable String displayName) {}

    private String encodePayload(String path, String kind, WriterMeta writer) {
        String displayName = writer.displayName();
        String encodedDisplay = displayName == null ? "" :
                Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(displayName.getBytes(StandardCharsets.UTF_8));
        return podId + "|" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(path.getBytes(StandardCharsets.UTF_8))
                + "|" + kind
                + "|" + (writer.editorId() == null ? "" : writer.editorId())
                + "|" + encodedDisplay
                + "|" + (writer.userId() == null ? "" : writer.userId());
    }

    private static @Nullable String decodePath(String encoded) {
        try {
            return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String wireKind(DocumentLiveChangedEvent.Kind kind) {
        return switch (kind) {
            case UPSERTED -> "upserted";
            case DELETED -> "deleted";
        };
    }

    /** Test hook: stable pod identity for self-echo assertions. */
    String podIdForTests() {
        return podId;
    }
}
