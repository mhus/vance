package de.mhus.vance.shared.tenant;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB repository for {@link TenantDocument}. Package-private — every access
 * from outside the {@code tenant} package goes through {@link TenantService}.
 */
interface TenantRepository extends MongoRepository<TenantDocument, String> {

    Optional<TenantDocument> findByName(String name);

    boolean existsByName(String name);
}
