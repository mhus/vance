package de.mhus.vance.shared.schema;

import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * What a {@link SchemaMigration} gets handed at run time.
 *
 * <p>{@code mongoTemplate} is here because a pure data backfill needs nothing
 * else — writing straight to the collection is the standard migration exception
 * to the service-datahoheit rule. A migration that needs domain services just
 * injects them as a normal bean.
 *
 * @param mongoTemplate collection-level access for backfills
 * @param migrationId   the id of the running migration, for log lines
 * @param ownerId       identity of the pod running it ({@code <host>/<uuid>})
 */
public record SchemaMigrationContext(
        MongoTemplate mongoTemplate,
        String migrationId,
        String ownerId) {}
