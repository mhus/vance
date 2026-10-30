package de.mhus.vance.shared.mail;

import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Mongo repository for {@link MailOutboxDocument}. Package-private — callers
 * go through {@link MailOutboxService}. The atomic claim uses
 * {@code MongoTemplate.findAndModify} there.
 */
interface MailOutboxRepository extends MongoRepository<MailOutboxDocument, String> {}
