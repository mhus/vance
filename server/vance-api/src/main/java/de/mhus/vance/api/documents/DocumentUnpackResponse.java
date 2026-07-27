package de.mhus.vance.api.documents;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of {@code POST /brain/{tenant}/documents/{id}/unpack}.
 *
 * <p>The server extracts a ZIP document into individual documents under
 * {@link #targetFolder}, streaming entry by entry. The summary reports what
 * happened without echoing every created path (those show up in the folder on
 * the next list load).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("documents")
public class DocumentUnpackResponse {

    /** Virtual folder the entries were extracted into. */
    private String targetFolder;

    /** Number of documents successfully created. */
    private int extracted;

    /** Entry paths skipped because a document already existed there. */
    private List<String> skipped;

    /** Entry paths rejected (unsafe path traversal) or that failed to write. */
    private List<String> failed;

    /**
     * True when extraction stopped early because the archive exceeded the
     * entry-count or total-size guard — {@link #extracted} then reflects only
     * what was written before the limit was hit.
     */
    private boolean truncated;
}
