package de.mhus.vance.shared.settings;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB repository for {@link SettingDocument}. Package-private — callers
 * go through {@link SettingService}.
 */
interface SettingRepository extends MongoRepository<SettingDocument, String> {

    Optional<SettingDocument> findByTenantIdAndReferenceTypeAndReferenceIdAndKey(
            String tenantId, String referenceType, String referenceId, String key);

    List<SettingDocument> findByTenantIdAndReferenceTypeAndReferenceId(
            String tenantId, String referenceType, String referenceId);

    List<SettingDocument> findByTenantIdAndKey(String tenantId, String key);

    boolean existsByTenantIdAndReferenceTypeAndReferenceIdAndKey(
            String tenantId, String referenceType, String referenceId, String key);

    void deleteByTenantIdAndReferenceTypeAndReferenceIdAndKey(
            String tenantId, String referenceType, String referenceId, String key);

    long deleteByTenantIdAndReferenceTypeAndReferenceId(
            String tenantId, String referenceType, String referenceId);
}
