package de.mhus.vance.shared.mail;

import de.mhus.vance.shared.crypto.AesEncryptionService;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Mail outbox: the data owner of {@link MailOutboxDocument}. Producers call
 * {@link #enqueue}; a sender loops {@link #claimNext} → {@link #markSent} /
 * {@link #markFailed}.
 *
 * <p>The claim is one atomic {@code findAndModify}, so several senders can
 * poll the same collection. An entry whose sender died stays {@code SENDING}
 * until its lease runs out and is then claimed again; the retry schedule
 * bounds how often that may happen.
 */
@Service
@Slf4j
public class MailOutboxService {

    private final MailOutboxRepository repository;
    private final MongoTemplate mongoTemplate;
    private final AesEncryptionService encryption;
    private final MailRetryPolicy retryPolicy;
    private final Duration lease;
    private final Duration retention;

    public MailOutboxService(
            MailOutboxRepository repository,
            MongoTemplate mongoTemplate,
            AesEncryptionService encryption,
            @Value("${vance.mail.outbox.retry-delays-seconds:5,20,60}") int[] retryDelaysSeconds,
            @Value("${vance.mail.outbox.lease-seconds:60}") long leaseSeconds,
            @Value("${vance.mail.outbox.retention-minutes:60}") long retentionMinutes) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
        this.encryption = encryption;
        this.retryPolicy = new MailRetryPolicy(retryDelaysSeconds);
        this.lease = Duration.ofSeconds(leaseSeconds);
        this.retention = Duration.ofMinutes(retentionMinutes);
    }

    /** Queues a mail for delivery as soon as a sender polls. Returns the entry id. */
    public String enqueue(String tenantId, String to, String subject, String body) {
        Instant now = Instant.now();
        MailOutboxDocument saved = repository.save(MailOutboxDocument.builder()
                .tenantId(tenantId)
                .to(to)
                .subject(subject)
                .bodyEncrypted(encryption.encrypt(body))
                .status(MailOutboxStatus.PENDING)
                .nextAttemptAt(now)
                .createdAt(now)
                .expiresAt(now.plus(retention))
                .build());
        return saved.getId() == null ? "" : saved.getId();
    }

    /**
     * Atomically takes the oldest due entry — {@code PENDING} and due, or
     * {@code SENDING} with an expired lease — and starts an attempt on it.
     * Empty when nothing is due.
     */
    public Optional<MailOutboxMessage> claimNext(Instant now) {
        Criteria due = new Criteria()
                .orOperator(
                        Criteria.where("status")
                                .is(MailOutboxStatus.PENDING)
                                .and("nextAttemptAt")
                                .lte(now),
                        Criteria.where("status")
                                .is(MailOutboxStatus.SENDING)
                                .and("leaseUntil")
                                .lt(now));
        Query query = new Query(due).with(Sort.by("nextAttemptAt")).limit(1);
        Update update = new Update()
                .set("status", MailOutboxStatus.SENDING)
                .set("leaseUntil", now.plus(lease))
                .inc("attempts", 1);
        MailOutboxDocument claimed = mongoTemplate.findAndModify(
                query, update, FindAndModifyOptions.options().returnNew(true), MailOutboxDocument.class);
        if (claimed == null || claimed.getId() == null) {
            return Optional.empty();
        }
        if (retryPolicy.exhausted(claimed.getAttempts() - 1)) {
            // A sender kept dying on this entry; stop claiming it again.
            giveUp(claimed, "lease expired too often", now);
            return Optional.empty();
        }
        String body = encryption.decrypt(claimed.getBodyEncrypted());
        return Optional.of(new MailOutboxMessage(
                claimed.getId(),
                claimed.getTenantId(),
                claimed.getTo(),
                claimed.getSubject(),
                body == null ? "" : body,
                claimed.getAttempts()));
    }

    /**
     * The mail was handed to SMTP: drop the body and keep the entry until it expires.
     * Applies only while {@code attempt} is still the running attempt — a sender
     * whose lease ran out and whose entry was claimed again changes nothing.
     */
    public void markSent(String id, int attempt, Instant now) {
        finishAttempt(
                id,
                attempt,
                new Update()
                        .set("status", MailOutboxStatus.SENT)
                        .set("sentAt", now)
                        .unset("bodyEncrypted")
                        .unset("leaseUntil")
                        .unset("lastError"));
    }

    /**
     * The attempt failed: schedule the next one, or give up when the schedule is
     * used up. Like {@link #markSent}, bound to the attempt that is still running.
     */
    public void markFailed(String id, int attempt, String error, Instant now) {
        if (retryPolicy.exhausted(attempt)) {
            log.warn("Mail outbox entry '{}' failed for good after {} attempts: {}", id, attempt, error);
            finishAttempt(
                    id,
                    attempt,
                    new Update()
                            .set("status", MailOutboxStatus.FAILED)
                            .set("lastError", error)
                            .set("expiresAt", now.plus(retention))
                            .unset("bodyEncrypted")
                            .unset("leaseUntil"));
            return;
        }
        finishAttempt(
                id,
                attempt,
                new Update()
                        .set("status", MailOutboxStatus.PENDING)
                        .set("nextAttemptAt", retryPolicy.nextAttemptAt(attempt, now))
                        .set("lastError", error)
                        .unset("leaseUntil"));
    }

    /** Writes the outcome of an attempt only if that attempt still owns the entry. */
    private void finishAttempt(String id, int attempt, Update update) {
        long changed = mongoTemplate
                .updateFirst(
                        new Query(Criteria.where("_id")
                                .is(id)
                                .and("status")
                                .is(MailOutboxStatus.SENDING)
                                .and("attempts")
                                .is(attempt)),
                        update,
                        MailOutboxDocument.class)
                .getModifiedCount();
        if (changed == 0) {
            log.debug("Mail outbox entry '{}' attempt {} no longer owns the entry; outcome dropped", id, attempt);
        }
    }

    private void giveUp(MailOutboxDocument doc, String error, Instant now) {
        log.warn(
                "Mail outbox entry '{}' to tenant '{}' failed for good after {} attempts: {}",
                doc.getId(),
                doc.getTenantId(),
                doc.getAttempts(),
                error);
        doc.setStatus(MailOutboxStatus.FAILED);
        doc.setBodyEncrypted(null);
        doc.setLeaseUntil(null);
        doc.setLastError(error);
        doc.setExpiresAt(now.plus(retention));
        repository.save(doc);
    }
}
