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
import java.util.Objects;
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
        String writerEditorId = event.editorId();
        log.trace("documents.changed[local] podId={} tenant={} path={} kind={} writerEditorId={} → publish + broadcast",
                podId, tenant, path, kind, writerEditorId);
        try {
            redis.publish(tenant, CHANNEL, encodePayload(path, kind, writerEditorId));
        } catch (RuntimeException ex) {
            log.warn("documents.changed[local] redis publish failed for '{}/{}': {}",
                    tenant, path, ex.toString());
        }
        broadcastLocal(path, kind, writerEditorId, "local");
    }

    private void onRemoteChanged(String topic, String body) {
        // Wire format: "{podId}|{base64(path)}|{kind}" (3 parts, legacy)
        //          or  "{podId}|{base64(path)}|{kind}|{editorId}" (4 parts).
        // editorId may be empty when the writer didn't supply the
        // X-Editor-Id header (server-side write, script, scheduler).
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
        String writerEditorId = parts.length >= 4 && !parts[3].isEmpty() ? parts[3] : null;
        log.trace("documents.changed[remote] from pod={} on topic={} path={} kind={} writerEditorId={}",
                senderPodId, topic, path, kind, writerEditorId);
        broadcastLocal(path, kind, writerEditorId, "remote");
    }

    private void broadcastLocal(String path, String kind, @Nullable String writerEditorId,
            String source) {
        if (!registry.hasLocalSubscribers(path)) {
            log.debug("documents.changed[{}] no local subscribers for path={} → drop", source, path);
            return;
        }
        DocumentChangedNotification payload = DocumentChangedNotification.builder()
                .path(path)
                .kind(kind)
                .build();
        WebSocketEnvelope envelope = WebSocketEnvelope.notification(
                MessageType.DOCUMENT_CHANGED, payload);

        int[] sent = {0};
        int[] skipped = {0};
        registry.forEachLocalSubscriber(path, (wsSession, ctx) -> {
            // Skip the writer's own connection — they just performed the
            // save themselves, they already have the freshest content.
            if (writerEditorId != null
                    && Objects.equals(ctx.getEditorId(), writerEditorId)) {
                skipped[0]++;
                log.trace("documents.changed[{}] skip writer ws='{}' editorId='{}' path={}",
                        source, wsSession.getId(), ctx.getEditorId(), path);
                return;
            }
            try {
                sender.sendOnChannel(wsSession, "documents", envelope);
                sent[0]++;
                log.trace("documents.changed[{}] → ws='{}' user='{}' editorId='{}' path={}",
                        source, wsSession.getId(), ctx.getUserId(), ctx.getEditorId(), path);
            } catch (IOException e) {
                log.debug("documents.changed[{}] push failed ws='{}' path='{}': {}",
                        source, wsSession.getId(), path, e.toString());
            }
        });
        log.trace("documents.changed[{}] fanOut path={} kind={} recipients={} skippedWriter={}",
                source, path, kind, sent[0], skipped[0]);
    }

    // ─── wire helpers ──────────────────────────────────────────────────

    private String encodePayload(String path, String kind, @Nullable String editorId) {
        return podId + "|" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(path.getBytes(StandardCharsets.UTF_8))
                + "|" + kind
                + "|" + (editorId == null ? "" : editorId);
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
