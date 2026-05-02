package de.mhus.vance.shared.enginemessage;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB repository for {@link EngineMessageDocument}. Package-private —
 * callers go through {@link EngineMessageService}.
 */
interface EngineMessageRepository extends MongoRepository<EngineMessageDocument, String> {

    Optional<EngineMessageDocument> findByMessageId(String messageId);

    /**
     * Outbox-replay query: messages from a set of sender processes that
     * have not yet been delivered (i.e. ack from receiver still missing).
     * Returns oldest-first so replay order matches creation order.
     */
    List<EngineMessageDocument> findBySenderProcessIdInAndDeliveredAtIsNullOrderByCreatedAtAsc(
            Collection<String> senderProcessIds);

    /**
     * Inbox-drain query: messages addressed to a process that have been
     * delivered but not yet drained by the lane. Returns oldest-first
     * so the lane processes them in arrival order.
     */
    List<EngineMessageDocument> findByTargetProcessIdAndDeliveredAtNotNullAndDrainedAtIsNullOrderByCreatedAtAsc(
            String targetProcessId);

    /**
     * Variant for boot-time recovery across many target processes at once.
     */
    List<EngineMessageDocument> findByTargetProcessIdInAndDeliveredAtNotNullAndDrainedAtIsNullOrderByCreatedAtAsc(
            Collection<String> targetProcessIds);

    long countByTargetProcessIdAndDeliveredAtNotNullAndDrainedAtIsNull(String targetProcessId);

    /**
     * All messages that haven't been delivered yet — used at boot to drive
     * the global outbox-replay pass. Each row's {@code senderProcessId}
     * tells the replay loop whether it's "ours" (local pod) or someone
     * else's job to retry.
     */
    List<EngineMessageDocument> findByDeliveredAtIsNullOrderByCreatedAtAsc();
}
