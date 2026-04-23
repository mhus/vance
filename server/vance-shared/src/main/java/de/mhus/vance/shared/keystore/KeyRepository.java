package de.mhus.vance.shared.keystore;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB repository for {@link KeyDocument}. Package-private — callers go through
 * {@link KeyService}.
 */
interface KeyRepository extends MongoRepository<KeyDocument, String> {

    Optional<KeyDocument> findByTenantIdAndPurposeAndKindAndKeyId(
            String tenantId, String purpose, KeyKind kind, String keyId);

    List<KeyDocument> findAllByTenantIdAndPurposeAndKindOrderByCreatedAtDesc(
            String tenantId, String purpose, KeyKind kind);

    Optional<KeyDocument> findTop1ByTenantIdAndPurposeAndKindOrderByCreatedAtDesc(
            String tenantId, String purpose, KeyKind kind);

    boolean existsByTenantIdAndPurposeAndKind(String tenantId, String purpose, KeyKind kind);

    long deleteByTenantIdAndPurpose(String tenantId, String purpose);
}
