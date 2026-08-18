package de.mhus.vance.shared.document;

import de.mhus.vance.shared.storage.StorageReferenceSource;
import java.util.Collection;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Live documents keep their blobs.
 *
 * <p>The knowledge was inside the orphan sweep; it sits here now so that a
 * deployment without documents — the kit store — simply does not
 * contribute it, instead of having to be excluded by hand.
 */
@Component
@RequiredArgsConstructor
public class DocumentStorageReferenceSource implements StorageReferenceSource {

    private final DocumentService documentService;

    @Override
    public Set<String> findReferencedStorageIds(Collection<String> candidates) {
        return documentService.findReferencedStorageIds(candidates);
    }

    @Override
    public String sourceName() {
        return "documents";
    }
}
