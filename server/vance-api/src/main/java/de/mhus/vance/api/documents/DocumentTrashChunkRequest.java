package de.mhus.vance.api.documents;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /brain/{tenant}/documents/trash-chunk}.
 *
 * <p>Chunked move-to-trash, same shape as the chunked move: the client drives
 * the loop, the server trashes one bounded chunk per call and skips anything it
 * cannot delete (no DELETE permission, reserved/privileged docs). Explicit
 * {@link #ids} not inside a selected folder are trashed on the first call
 * (blank cursor); {@link #folders} are keyset-scanned by path — the client
 * passes the returned cursor back until {@code done}. Trashed documents leave
 * the folder prefix, so the loop is O(N) and terminates.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("documents")
public class DocumentTrashChunkRequest {

    /** Explicit document ids (trashed on the first call, i.e. blank cursor). */
    private @Nullable List<String> ids;

    /** Folder prefixes to drain (each should end with {@code '/'}). */
    private @Nullable List<String> folders;

    /** Max documents to process this call. Server clamps to a sane range. */
    private @Nullable Integer limit;

    /** Keyset cursor (last processed path) from the previous response. */
    private @Nullable String cursor;
}
