package de.mhus.vance.shared.user;

import java.util.Optional;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Data owner of {@link EmailLoginCodeDocument}: the open e-mail codes. All
 * changes are single atomic operations, so concurrent callers cannot both
 * count past the attempt limit or both consume the same code.
 */
@Service
public class EmailLoginCodeService {

    private static final String F_TENANT_ID = "tenantId";
    private static final String F_USER_ID = "userId";
    private static final String F_PURPOSE = "purpose";
    private static final String F_TRANSACTION_ID = "transactionId";

    private final MongoTemplate mongoTemplate;

    public EmailLoginCodeService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /** Stores {@code code} as the open code of its account and purpose, replacing an earlier one. */
    public void replace(EmailLoginCodeDocument code) {
        mongoTemplate.upsert(
                slot(code.getTenantId(), code.getUserId(), code.getPurpose()),
                new Update()
                        .set(F_TRANSACTION_ID, code.getTransactionId())
                        .set("tokenHash", code.getTokenHash())
                        .set("address", code.getAddress())
                        .set("requestedAt", code.getRequestedAt())
                        .set("expiresAt", code.getExpiresAt())
                        .set("attempts", 0),
                EmailLoginCodeDocument.class);
    }

    /** The open code of the account for {@code purpose}, if any. */
    public Optional<EmailLoginCodeDocument> find(String tenantId, String userId, EmailLoginPurpose purpose) {
        return Optional.ofNullable(
                mongoTemplate.findOne(slot(tenantId, userId, purpose), EmailLoginCodeDocument.class));
    }

    /**
     * Atomically counts one verification attempt, but only if the open code is
     * still {@code transactionId}.
     *
     * @return the code with the new count, empty if it is gone or was replaced
     */
    public Optional<EmailLoginCodeDocument> countAttempt(
            String tenantId, String userId, EmailLoginPurpose purpose, String transactionId) {
        return Optional.ofNullable(mongoTemplate.findAndModify(
                transaction(tenantId, userId, purpose, transactionId),
                new Update().inc("attempts", 1),
                FindAndModifyOptions.options().returnNew(true),
                EmailLoginCodeDocument.class));
    }

    /**
     * Atomically removes the open code if it is still {@code transactionId}.
     * Two concurrent callers cannot both get {@code true}, which makes a code
     * single-use.
     */
    public boolean consume(String tenantId, String userId, EmailLoginPurpose purpose, String transactionId) {
        return mongoTemplate.findAndRemove(
                        transaction(tenantId, userId, purpose, transactionId), EmailLoginCodeDocument.class)
                != null;
    }

    private static Query slot(String tenantId, String userId, EmailLoginPurpose purpose) {
        return new Query(Criteria.where(F_TENANT_ID)
                .is(tenantId)
                .and(F_USER_ID)
                .is(userId)
                .and(F_PURPOSE)
                .is(purpose));
    }

    private static Query transaction(String tenantId, String userId, EmailLoginPurpose purpose, String transactionId) {
        return new Query(Criteria.where(F_TENANT_ID)
                .is(tenantId)
                .and(F_USER_ID)
                .is(userId)
                .and(F_PURPOSE)
                .is(purpose)
                .and(F_TRANSACTION_ID)
                .is(transactionId));
    }
}
