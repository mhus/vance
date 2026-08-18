package de.mhus.vance.shared.document;

import de.mhus.vance.shared.storage.StorageReferenceSource;
import java.util.Collection;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Archived versions keep their blobs too.
 *
 * <p>Separate from {@link DocumentStorageReferenceSource} because they are
 * separate claims: an archive can be the only thing still pointing at a
 * blob whose live document was replaced.
 */
@Component
@RequiredArgsConstructor
public class ArchiveStorageReferenceSource implements StorageReferenceSource {

    private final DocumentArchiveService archiveService;

    @Override
    public Set<String> findReferencedStorageIds(Collection<String> candidates) {
        return archiveService.findReferencedStorageIds(candidates);
    }

    @Override
    public String sourceName() {
        return "document archives";
    }
}
