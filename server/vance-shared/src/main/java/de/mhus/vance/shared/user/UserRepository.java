package de.mhus.vance.shared.user;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB repository for {@link UserDocument}. Package-private — callers go
 * through {@link UserService}.
 */
interface UserRepository extends MongoRepository<UserDocument, String> {

    Optional<UserDocument> findByTenantIdAndName(String tenantId, String name);

    boolean existsByTenantIdAndName(String tenantId, String name);

    List<UserDocument> findByTenantId(String tenantId);
}
