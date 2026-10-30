package de.mhus.vance.shared.resourceoverride;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Owner of {@link ResourceOverrideDocument}: the versions of the centrally
 * managed resource areas. Brains read the valid version ({@link #effective},
 * {@link #findEffective}); the cloud addon of Anus writes ({@link #publish},
 * {@link #setEnabled}, {@link #prune}).
 *
 * <p>The valid version of a type is the highest enabled one. Reads that only
 * decide whether anything changed leave the files out of the query.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceOverrideService {

    /** Retries for a version number another writer took first. */
    private static final int PUBLISH_ATTEMPTS = 5;

    private final ResourceOverrideRepository repository;
    private final MongoTemplate mongoTemplate;

    /** The valid version of {@code type} without its files; empty when no version is enabled. */
    public Optional<ResourceOverrideRef> effective(String type) {
        Query query = validQuery(type);
        query.fields().exclude("files");
        return Optional.ofNullable(mongoTemplate.findOne(query, ResourceOverrideDocument.class))
                .map(doc ->
                        new ResourceOverrideRef(doc.getType(), doc.getVersion(), doc.isOverwrite(), doc.getDigest()));
    }

    /** The valid version of {@code type} including its files. */
    public Optional<ResourceOverrideDocument> findEffective(String type) {
        return Optional.ofNullable(mongoTemplate.findOne(validQuery(type), ResourceOverrideDocument.class));
    }

    /** One version including its files. */
    public Optional<ResourceOverrideDocument> find(String type, long version) {
        return repository.findByTypeAndVersion(type, version);
    }

    /** All versions of {@code type}, newest first, without their files. */
    public List<ResourceOverrideDocument> history(String type) {
        Query query = Query.query(Criteria.where("type").is(type)).with(Sort.by(Sort.Direction.DESC, "version"));
        query.fields().exclude("files");
        return mongoTemplate.find(query, ResourceOverrideDocument.class);
    }

    /**
     * Append a new enabled version. {@code overwrite=false} records "use the
     * bundled resources" and carries no files.
     *
     * @throws IllegalArgumentException for an unregistered type, or {@code overwrite=true} without files
     */
    public ResourceOverrideDocument publish(
            String type,
            List<ResourceOverrideFile> files,
            boolean overwrite,
            @Nullable String createdBy,
            @Nullable String note) {
        requireRegistered(type);
        if (overwrite && files.isEmpty()) {
            throw new IllegalArgumentException("A version with overwrite=true needs at least one file");
        }
        List<ResourceOverrideFile> stored = overwrite ? List.copyOf(files) : List.of();
        String digest = ResourceOverrideDigest.of(stored);
        DuplicateKeyException lastClash = null;
        for (int attempt = 0; attempt < PUBLISH_ATTEMPTS; attempt++) {
            ResourceOverrideDocument doc = ResourceOverrideDocument.builder()
                    .type(type)
                    .version(highestVersion(type) + 1)
                    .enabled(true)
                    .overwrite(overwrite)
                    .files(new ArrayList<>(stored))
                    .fileCount(stored.size())
                    .digest(digest)
                    .createdAt(Instant.now())
                    .createdBy(createdBy)
                    .note(note)
                    .build();
            try {
                return repository.insert(doc);
            } catch (DuplicateKeyException e) {
                lastClash = e;
                log.debug(
                        "ResourceOverride: version {} of '{}' taken by another writer, retrying",
                        doc.getVersion(),
                        type);
            }
        }
        throw new IllegalStateException("Could not allocate a version for '" + type + "'", lastClash);
    }

    /** Take a version out of the choice of the valid one, or back in. @return whether it exists */
    public boolean setEnabled(String type, long version, boolean enabled) {
        Query query = Query.query(Criteria.where("type").is(type).and("version").is(version));
        return mongoTemplate
                        .updateFirst(query, Update.update("enabled", enabled), ResourceOverrideDocument.class)
                        .getMatchedCount()
                > 0;
    }

    /**
     * Delete all but the newest {@code keep} versions of {@code type}. The valid
     * version is never deleted, so a pruning pass cannot change what applies.
     *
     * @return number of deleted versions
     */
    public int prune(String type, int keep) {
        if (keep < 0) {
            throw new IllegalArgumentException("keep must not be negative: " + keep);
        }
        long validVersion = effective(type).map(ResourceOverrideRef::version).orElse(-1L);
        int deleted = 0;
        List<ResourceOverrideDocument> all = history(type);
        for (int i = keep; i < all.size(); i++) {
            ResourceOverrideDocument old = all.get(i);
            if (old.getVersion() == validVersion) {
                continue;
            }
            repository.deleteByTypeAndVersion(type, old.getVersion());
            deleted++;
        }
        return deleted;
    }

    private long highestVersion(String type) {
        Query query = Query.query(Criteria.where("type").is(type)).with(Sort.by(Sort.Direction.DESC, "version"));
        query.fields().include("version");
        ResourceOverrideDocument top = mongoTemplate.findOne(query, ResourceOverrideDocument.class);
        return top == null ? 0 : top.getVersion();
    }

    private static Query validQuery(String type) {
        return Query.query(Criteria.where("type").is(type).and("enabled").is(true))
                .with(Sort.by(Sort.Direction.DESC, "version"))
                .limit(1);
    }

    private static void requireRegistered(String type) {
        if (!ResourceOverrideSections.isRegistered(type)) {
            throw new IllegalArgumentException(
                    "Unknown resource area '" + type + "'; known: " + ResourceOverrideSections.registered());
        }
    }
}
