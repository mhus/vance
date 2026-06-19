package de.mhus.vance.brain.ws.documents;

import de.mhus.vance.api.documents.DocumentNoteDto;
import de.mhus.vance.api.ws.DocumentNoteChangedNotification;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.shared.document.DocumentNote;
import de.mhus.vance.shared.document.DocumentNotesChangedEvent;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Bridges {@link DocumentNotesChangedEvent} (fired by
 * {@code DocumentService} after note CRUD) and the {@code documents} WS
 * channel — mirror of {@code DocumentChangedBroadcaster} for sticky-note
 * mutations. Last-writer-wins; no conflict handling, the client just
 * merges the latest version into its local map.
 *
 * <p>Wire format on Redis: variable-length pipe-delimited frames so the
 * payload can carry the note JSON without re-escaping pipes.
 *
 * <pre>{@code
 *   {podId}|{base64url(path)}|{kind}|{noteId}|{base64url(noteJson?)}|{editorId?}
 * }</pre>
 *
 * <p>Cross-pod handler ignores its own publishes via {@code podId}; the
 * local fan-out skips the writer's own WebSocket via {@code editorId}.
 */
@Service
@Slf4j
public class DocumentNotesBroadcaster {

    static final String CHANNEL = "documents.notes";

    private final DocumentSubscriberRegistry registry;
    private final VanceRedisMessagingService redis;
    private final WebSocketSender sender;
    private final ObjectMapper objectMapper;

    /** Per-process identity used to skip our own Redis pub/sub echoes. */
    private final String podId = UUID.randomUUID().toString();

    public DocumentNotesBroadcaster(
            DocumentSubscriberRegistry registry,
            VanceRedisMessagingService redis,
            WebSocketSender sender,
            ObjectMapper objectMapper) {
        this.registry = registry;
        this.redis = redis;
        this.sender = sender;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void start() {
        redis.subscribeAcrossTenants(CHANNEL, this::onRemoteChanged);
        log.debug("DocumentNotesBroadcaster: podId={} redis.enabled={}",
                podId, redis.isEnabled());
    }

    @PreDestroy
    public void stop() {
        redis.unsubscribeAcrossTenants(CHANNEL);
    }

    @EventListener
    public void onLocalChanged(DocumentNotesChangedEvent event) {
        String tenant = event.tenantId();
        String path = event.path();
        String kind = wireKind(event.kind());
        log.trace("documents.notes[local] podId={} tenant={} path={} kind={} noteId={} editorId={}",
                podId, tenant, path, kind, event.noteId(), event.editorId());
        try {
            redis.publish(tenant, CHANNEL,
                    encodePayload(path, kind, event.noteId(), event.note(), event.editorId()));
        } catch (RuntimeException ex) {
            log.warn("documents.notes[local] redis publish failed for '{}/{}': {}",
                    tenant, path, ex.toString());
        }
        broadcastLocal(path, kind, event.noteId(), event.note(), event.editorId(), "local");
    }

    private void onRemoteChanged(String topic, String body) {
        // Wire format: 6 pipe-delimited parts (see class doc). Empty
        // strings are valid for the trailing optional fields.
        String[] parts = body.split("\\|", -1);
        if (parts.length < 4) {
            log.debug("documents.notes[remote] malformed body on {}: '{}'", topic, body);
            return;
        }
        String senderPodId = parts[0];
        if (Objects.equals(senderPodId, podId)) {
            log.trace("documents.notes[remote] self-echo on {} ignored", topic);
            return;
        }
        String path = decodeUrl(parts[1]);
        if (path == null) {
            log.debug("documents.notes[remote] undecodable path on {}: '{}'", topic, parts[1]);
            return;
        }
        String kind = parts[2];
        String noteId = parts[3];
        DocumentNote note = null;
        if (parts.length >= 5 && !parts[4].isEmpty()) {
            String noteJson = decodeUrl(parts[4]);
            if (noteJson != null) {
                try {
                    note = objectMapper.readValue(noteJson, DocumentNote.class);
                } catch (JacksonException e) {
                    log.debug("documents.notes[remote] malformed note JSON on {}: {}",
                            topic, e.toString());
                    return;
                }
            }
        }
        String editorId = parts.length >= 6 && !parts[5].isEmpty() ? parts[5] : null;
        log.trace("documents.notes[remote] from pod={} path={} kind={} noteId={} editorId={}",
                senderPodId, path, kind, noteId, editorId);
        broadcastLocal(path, kind, noteId, note, editorId, "remote");
    }

    private void broadcastLocal(
            String path,
            String kind,
            String noteId,
            @Nullable DocumentNote note,
            @Nullable String writerEditorId,
            String source) {
        if (!registry.hasLocalSubscribers(path)) {
            log.debug("documents.notes[{}] no local subscribers for path={} → drop", source, path);
            return;
        }
        DocumentNoteChangedNotification payload = DocumentNoteChangedNotification.builder()
                .path(path)
                .kind(kind)
                .noteId(noteId)
                .note(note == null ? null : toDto(note))
                .build();
        WebSocketEnvelope envelope = WebSocketEnvelope.notification(
                MessageType.DOCUMENT_NOTE_CHANGED, payload);

        int[] sent = {0};
        int[] skipped = {0};
        registry.forEachLocalSubscriber(path, (wsSession, ctx) -> {
            if (writerEditorId != null
                    && Objects.equals(ctx.getEditorId(), writerEditorId)) {
                skipped[0]++;
                return;
            }
            try {
                sender.sendOnChannel(wsSession, "documents", envelope);
                sent[0]++;
            } catch (IOException e) {
                log.debug("documents.notes[{}] push failed ws='{}' path='{}': {}",
                        source, wsSession.getId(), path, e.toString());
            }
        });
        log.trace("documents.notes[{}] fanOut path={} kind={} noteId={} recipients={} skippedWriter={}",
                source, path, kind, noteId, sent[0], skipped[0]);
    }

    // ─── helpers ──────────────────────────────────────────────────────

    private String encodePayload(
            String path,
            String kind,
            String noteId,
            @Nullable DocumentNote note,
            @Nullable String editorId) {
        String encodedNote = "";
        if (note != null) {
            try {
                String json = objectMapper.writeValueAsString(note);
                encodedNote = Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(json.getBytes(StandardCharsets.UTF_8));
            } catch (JacksonException e) {
                log.warn("documents.notes encode failed: {}", e.toString());
            }
        }
        return podId + "|" + encodeUrl(path)
                + "|" + kind
                + "|" + noteId
                + "|" + encodedNote
                + "|" + (editorId == null ? "" : editorId);
    }

    private static String encodeUrl(String s) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static @Nullable String decodeUrl(String encoded) {
        try {
            return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String wireKind(DocumentNotesChangedEvent.Kind kind) {
        return switch (kind) {
            case ADDED -> "added";
            case UPDATED -> "updated";
            case DELETED -> "deleted";
        };
    }

    private static DocumentNoteDto toDto(DocumentNote n) {
        return DocumentNoteDto.builder()
                .id(n.getId())
                .text(n.getText())
                .userId(n.getUserId())
                .createdAtMs(n.getCreatedAt() == null ? 0L : n.getCreatedAt().toEpochMilli())
                .updatedAtMs(n.getUpdatedAt() == null ? 0L : n.getUpdatedAt().toEpochMilli())
                .done(n.isDone())
                .line(n.getLine())
                .order(n.getOrder())
                .build();
    }

    /** Test hook: stable pod identity for self-echo assertions. */
    String podIdForTests() {
        return podId;
    }
}
