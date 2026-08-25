package de.mhus.vance.api.documents;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /brain/{tenant}/documents/copy-chunk}.
 *
 * <p>Chunked copy: the client drives the loop, the server executes one bounded
 * chunk per call and skips anything it cannot copy (no READ permission on the
 * source, no CREATE permission on the destination, or a name collision in the
 * target project — unless {@link #overwrite} is set). The structure mirrors
 * {@link DocumentMoveChunkRequest} but
 * adds {@link #targetProjectId} — copy is the only bulk operation that can
 * cross project boundaries.
 *
 * <p>Documents given by {@link #ids} that are not inside a selected folder are
 * copied on the first call ({@code cursor} blank); {@link #folders} are
 * keyset-scanned by path — the client passes the returned cursor back on each
 * call until {@code done}. Unlike move, copied documents stay in place, so the
 * cursor simply advances past processed ones and the loop is O(N).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("documents")
public class DocumentCopyChunkRequest {

    /** Explicit document ids (copied on the first call, i.e. blank cursor). */
    private @Nullable List<String> ids;

    /** Folder prefixes to drain (each should end with {@code '/'}). */
    private @Nullable List<String> folders;

    /** Destination project ({@code null} or blank = same project as the source). */
    private @Nullable String targetProjectId;

    /** Destination folder (empty string = project root). */
    @NotNull
    private String targetFolder;

    /**
     * Replace documents that already exist at the destination instead of
     * skipping them ({@code null} = {@code false}). Overwriting is an edit of
     * the target, not a create: it needs {@code WRITE} on the destination and
     * respects the document lock, so a locked or unwritable target is still
     * skipped.
     */
    private @Nullable Boolean overwrite;

    /** Max documents to process this call. Server clamps to a sane range. */
    private @Nullable Integer limit;

    /** Keyset cursor (last processed path) from the previous response. */
    private @Nullable String cursor;
}
