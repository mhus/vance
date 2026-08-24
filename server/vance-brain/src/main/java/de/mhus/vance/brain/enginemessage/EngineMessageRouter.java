package de.mhus.vance.brain.enginemessage;

import de.mhus.vance.brain.project.ProjectManagerService;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.shared.enginemessage.EngineMessageDocument;
import de.mhus.vance.shared.enginemessage.EngineMessageService;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.PendingMessageMapper;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * High-level engine-to-engine dispatch. Decides between local-direct
 * and cross-pod-WS routing based on the target process's Home Pod and
 * routes accordingly.
 *
 * <h2>Local-direct path</h2>
 * Target's Home Pod is this brain process: persist via
 * {@link EngineMessageService#acceptDelivery} (idempotent) and schedule
 * a lane turn through {@link ProcessEventEmitter#scheduleTurn}. Same as
 * the legacy {@code ThinkProcessService.appendPending} path; one less
 * indirection.
 *
 * <h2>Cross-pod path</h2>
 * Target's Home Pod is a different brain process:
 * <ol>
 *   <li>{@link EngineMessageService#insertOutbox} writes the message
 *       with {@code deliveredAt = null} so a sender crash leaves a
 *       resumable outbox entry for the boot-time replay.</li>
 *   <li>{@link EngineWsClient#send} pushes the frame to the Home Pod's
 *       {@code /internal/engine-bind} and waits for the ack.</li>
 *   <li>The receiver's {@code acceptDelivery} finds the row, sets
 *       {@code deliveredAt}, and acks back. From here the sender's
 *       outbox view shows {@code deliveredAt} populated too — the
 *       collection is shared.</li>
 * </ol>
 *
 * <p>WebSocket failure leaves the outbox row in place so the boot-time
 * {@code EngineMessageReplayer} (or a future periodic retry loop) can
 * push it again. Receiver-side dedup makes the retry safe.
 *
 * <p>Replaces direct calls to {@code thinkProcessService.appendPending}
 * + {@code eventEmitter.scheduleTurn} at engine-to-engine call sites.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EngineMessageRouter {

    private static final Duration WS_SEND_TIMEOUT = Duration.ofSeconds(10);

    private final EngineMessageService engineMessageService;
    private final ThinkProcessService thinkProcessService;
    private final ProjectManagerService projectManager;
    private final ProcessEventEmitter eventEmitter;
    private final EngineWsClient engineWsClient;

    /**
     * Routes {@code message} to {@code targetProcessId}. Returns
     * {@code true} when the message was durably accepted by the
     * receiver (local insert, or cross-pod ack).
     */
    public boolean dispatch(
            @Nullable String senderProcessId,
            String targetProcessId,
            PendingMessageDocument message) {

        Optional<ThinkProcessDocument> targetOpt = thinkProcessService.findById(targetProcessId);
        if (targetOpt.isEmpty()) {
            log.warn("dispatch: target process not found id='{}'", targetProcessId);
            return false;
        }
        ThinkProcessDocument target = targetOpt.get();

        // One mapping for both routes into EngineMessageDocument. This used to
        // be a private copy here, and the copy is what dropped
        // fromUserDisplayName and activeInbox: both were added to the
        // ThinkProcessService bridge when they were introduced, and a field
        // that stops at this hop simply arrives as null at the engine, with
        // nothing reporting it. See PendingMessageMapper.
        EngineMessageDocument doc = PendingMessageMapper.toEngineMessage(
                message, targetProcessId, target.getTenantId(),
                senderProcessId == null ? "" : senderProcessId);

        Optional<String> homeEndpoint = projectManager.findProjectEndpoint(
                target.getTenantId(), target.getProjectId());
        boolean local = homeEndpoint.isEmpty() || projectManager.isLocalPod(homeEndpoint.get());

        if (local) {
            return dispatchLocal(doc);
        }
        return dispatchCrossPod(doc, homeEndpoint.get());
    }

    private boolean dispatchLocal(EngineMessageDocument doc) {
        engineMessageService.acceptDelivery(doc);
        eventEmitter.scheduleTurn(doc.getTargetProcessId());
        log.debug("dispatch local: target={} messageId={} type={}",
                doc.getTargetProcessId(), doc.getMessageId(), doc.getType());
        return true;
    }

    private boolean dispatchCrossPod(EngineMessageDocument doc, String homeEndpoint) {
        // Outbox first — survives a sender crash between insert and ack.
        try {
            engineMessageService.insertOutbox(doc);
        } catch (DuplicateKeyException replay) {
            log.debug("dispatch cross-pod: messageId={} already in outbox — retrying push",
                    doc.getMessageId());
        }
        try {
            EngineWsAck ack = engineWsClient.send(homeEndpoint, doc, WS_SEND_TIMEOUT);
            if (!EngineWsAck.STATUS_ACK.equals(ack.status())) {
                log.warn("dispatch cross-pod: receiver rejected messageId={} target={}: {}",
                        doc.getMessageId(), homeEndpoint, ack.reason());
                return false;
            }
            log.debug("dispatch cross-pod: target={} via {} messageId={} type={}",
                    doc.getTargetProcessId(), homeEndpoint, doc.getMessageId(), doc.getType());
            return true;
        } catch (EngineWsClient.EngineWsException e) {
            log.warn("dispatch cross-pod: push failed messageId={} target={} via {}: {}",
                    doc.getMessageId(), doc.getTargetProcessId(), homeEndpoint, e.toString());
            // Outbox keeps the row; replay handles retry.
            return false;
        }
    }
}
