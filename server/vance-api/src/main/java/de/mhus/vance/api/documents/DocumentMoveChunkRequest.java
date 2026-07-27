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
 * Body of {@code POST /brain/{tenant}/documents/move-chunk}.
 *
 * <p>Chunked move: the client drives the loop, the server executes one bounded
 * chunk per call and skips anything it cannot move (no WRITE permission, name
 * collision, or a folder cycle). Documents given by {@link #ids} that are not
 * inside a selected folder are moved on the first call ({@code cursor} blank);
 * {@link #folders} are keyset-scanned by path — the client passes the returned
 * cursor back on each call until {@code done}. Because moved documents leave
 * the prefix and the cursor advances past skipped ones, the loop is O(N) and
 * cannot repeat work.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("documents")
public class DocumentMoveChunkRequest {

    /** Explicit document ids (moved on the first call, i.e. blank cursor). */
    private @Nullable List<String> ids;

    /** Folder prefixes to drain (each should end with {@code '/'}). */
    private @Nullable List<String> folders;

    /** Destination folder (empty string = project root). */
    @NotNull
    private String targetFolder;

    /** Max documents to process this call. Server clamps to a sane range. */
    private @Nullable Integer limit;

    /** Keyset cursor (last processed path) from the previous response. */
    private @Nullable String cursor;
}
