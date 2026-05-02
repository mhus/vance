package de.mhus.vance.shared.enginemessage;

import com.mongodb.client.result.UpdateResult;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.LinkedHashSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Lifecycle and lookup for {@link EngineMessageDocument}. The single
 * entry point to engine-message data.
 *
 * <h2>Roles</h2>
 *
 * <ul>
 *   <li><b>Sender</b> calls {@link #insertOutbox(EngineMessageDocument)}
 *       to persist a new message before the WebSocket push, and later
 *       {@link #findByMessageId(String)} to check whether the receiver
 *       has acked.</li>
 *   <li><b>Receiver</b> calls
 *       {@link #acceptDelivery(EngineMessageDocument)} on each incoming
 *       frame; the method is idempotent in {@code messageId}, so a
 *       sender retry after a missed ack does not cause duplicate
 *       processing on the lane.</li>
 *   <li><b>Receiver lane</b> calls {@link #drainInbox(String)} at the
 *       start of a lane-turn to fetch all delivered-but-not-drained
 *       messages, then {@link #markDrained(Collection)} to mark them
 *       consumed.</li>
 *   <li><b>Boot recovery</b> calls
 *       {@link #findOutboxedBySenders(Collection)} and
 *       {@link #findInboxedByTargets(Collection)} to find work that
 *       must be replayed or drained after a pod restart.</li>
 * </ul>
 *
 * <p>See {@code specification/engine-message-routing.md} §3, §7, §8.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EngineMessageService {

    private static final String F_ID = "_id";
    private static final String F_DELIVERED_AT = "deliveredAt";
    private static final String F_DRAINED_AT = "drainedAt";

    private final EngineMessageRepository repository;
    private final MongoTemplate mongoTemplate;

    // ─────────────────── Sender ───────────────────

    /**
     * Persists a freshly produced message into the sender's outbox.
     * Sets {@link EngineMessageDocument#getCreatedAt()} if unset.
     *
     * @throws DuplicateKeyException if {@code messageId} already exists —
     *     the sender is responsible for assigning unique IDs (typically
     *     ULID/UUID).
     */
    public EngineMessageDocument insertOutbox(EngineMessageDocument message) {
        if (message.getMessageId() == null || message.getMessageId().isBlank()) {
            throw new IllegalArgumentException("messageId must be set by the sender");
        }
        if (message.getCreatedAt() == null || message.getCreatedAt().equals(Instant.EPOCH)) {
            message.setCreatedAt(Instant.now());
        }
        try {
            return repository.insert(message);
        } catch (DuplicateKeyException e) {
            log.debug("Duplicate insertOutbox for messageId={} — caller may be replaying after a crash",
                    message.getMessageId());
            throw e;
        }
    }

    // ─────────────────── Receiver ───────────────────

    /**
     * Idempotent delivery acceptance. If no document with this
     * {@code messageId} exists, inserts the incoming one with
     * {@link EngineMessageDocument#getDeliveredAt()} set to now. If it
     * exists with {@code deliveredAt == null}, updates the timestamp.
     * If it already has {@code deliveredAt} set, this is a duplicate
     * delivery from a sender retry — no-op, just return the existing
     * document.
     *
     * <p>The caller acks unconditionally to the sender after this
     * returns; ack-on-persist semantics are guaranteed by the
     * unconditional update / insert path.
     *
     * @return the persisted document (existing or newly inserted)
     */
    public EngineMessageDocument acceptDelivery(EngineMessageDocument incoming) {
        Instant now = Instant.now();
        Optional<EngineMessageDocument> existing = repository.findByMessageId(incoming.getMessageId());
        if (existing.isPresent()) {
            EngineMessageDocument doc = existing.get();
            if (doc.getDeliveredAt() == null) {
                Query q = Query.query(Criteria.where(F_ID).is(doc.getMessageId())
                        .and(F_DELIVERED_AT).isNull());
                Update u = Update.update(F_DELIVERED_AT, now);
                UpdateResult r = mongoTemplate.updateFirst(q, u, EngineMessageDocument.class);
                if (r.getModifiedCount() == 1) {
                    doc.setDeliveredAt(now);
                }
            }
            return doc;
        }
        if (incoming.getCreatedAt() == null || incoming.getCreatedAt().equals(Instant.EPOCH)) {
            incoming.setCreatedAt(now);
        }
        incoming.setDeliveredAt(now);
        try {
            return repository.insert(incoming);
        } catch (DuplicateKeyException race) {
            // Someone else inserted between our findByMessageId and insert; treat as
            // duplicate delivery and return the now-existing document.
            log.debug("Race on acceptDelivery for messageId={} — re-reading", incoming.getMessageId());
            return repository.findByMessageId(incoming.getMessageId()).orElseThrow(() -> race);
        }
    }

    /**
     * Returns all messages addressed to {@code targetProcessId} that
     * have been delivered but not yet drained, oldest-first. Used by
     * the lane at the start of each turn.
     */
    public List<EngineMessageDocument> drainInbox(String targetProcessId) {
        return repository
                .findByTargetProcessIdAndDeliveredAtNotNullAndDrainedAtIsNullOrderByCreatedAtAsc(
                        targetProcessId);
    }

    /**
     * Marks the given messages as drained. Called by the lane after
     * the engine has consumed them. Idempotent — already-drained
     * messages keep their original timestamp.
     */
    public void markDrained(Collection<String> messageIds) {
        if (messageIds.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        Query q = Query.query(Criteria.where(F_ID).in(messageIds)
                .and(F_DRAINED_AT).isNull());
        Update u = Update.update(F_DRAINED_AT, now);
        UpdateResult r = mongoTemplate.updateMulti(q, u, EngineMessageDocument.class);
        log.trace("markDrained: {} of {} messages updated", r.getModifiedCount(), messageIds.size());
    }

    // ─────────────────── Recovery (boot) ───────────────────

    /**
     * Returns messages that the given senders persisted but never
     * received an ack for — they need to be re-pushed on a fresh WS.
     */
    public List<EngineMessageDocument> findOutboxedBySenders(Collection<String> senderProcessIds) {
        if (senderProcessIds.isEmpty()) {
            return List.of();
        }
        return repository
                .findBySenderProcessIdInAndDeliveredAtIsNullOrderByCreatedAtAsc(senderProcessIds);
    }

    /**
     * Returns messages addressed to any of the given target processes
     * that arrived before the pod went down — they need to be drained
     * by the receiver lane on resume.
     */
    public List<EngineMessageDocument> findInboxedByTargets(Collection<String> targetProcessIds) {
        if (targetProcessIds.isEmpty()) {
            return List.of();
        }
        return repository
                .findByTargetProcessIdInAndDeliveredAtNotNullAndDrainedAtIsNullOrderByCreatedAtAsc(
                        targetProcessIds);
    }

    // ─────────────────── Lookup ───────────────────

    public Optional<EngineMessageDocument> findByMessageId(String messageId) {
        return repository.findByMessageId(messageId);
    }

    public long countInbox(String targetProcessId) {
        return repository.countByTargetProcessIdAndDeliveredAtNotNullAndDrainedAtIsNull(
                targetProcessId);
    }

    /**
     * Returns the distinct {@code targetProcessId}s of all messages that
     * have been delivered but not yet drained — used at brain boot to
     * find processes whose lanes need a wakeup so they catch up on work
     * accumulated while the pod was down.
     *
     * <p>Iteration order is not guaranteed; callers usually filter
     * down to processes owned by the local pod before scheduling lane
     * turns.
     */
    /**
     * All outboxed messages across the whole engine_messages collection —
     * messages a sender has persisted but for which no ack has come back.
     * Used at brain boot to find work that needs to be re-pushed; the
     * caller filters by sender's Home Pod to avoid stealing replay duty
     * from peers.
     */
    public List<EngineMessageDocument> findAllOutboxed() {
        return repository.findByDeliveredAtIsNullOrderByCreatedAtAsc();
    }

    public Set<String> findPendingTargetProcessIds() {
        Query q = Query.query(Criteria
                .where(F_DELIVERED_AT).ne(null)
                .and(F_DRAINED_AT).is(null));
        List<String> distinct = mongoTemplate.findDistinct(
                q, "targetProcessId", EngineMessageDocument.class, String.class);
        return new LinkedHashSet<>(distinct);
    }
}
