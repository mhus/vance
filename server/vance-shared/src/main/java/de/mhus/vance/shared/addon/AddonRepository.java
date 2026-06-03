package de.mhus.vance.shared.addon;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB repository for {@link AddonDocument}. Package-private —
 * external access goes through {@link AddonService}.
 */
interface AddonRepository extends MongoRepository<AddonDocument, String> {

    Optional<AddonDocument> findByName(String name);

    List<AddonDocument> findByEnabledTrueOrderByNameAsc();

    List<AddonDocument> findAllByOrderByNameAsc();

    boolean existsByName(String name);
}
