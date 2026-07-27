package de.mhus.vance.api.documents;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Result of one {@code POST /brain/{tenant}/documents/trash-chunk} call. The
 * client accumulates {@link #trashed} / {@link #skipped}, passes {@link #cursor}
 * back into the next request, and stops when {@link #done} is {@code true}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("documents")
public class DocumentTrashChunkResponse {

    /** Documents moved to trash in this chunk. */
    private int trashed;

    /** Documents skipped in this chunk (no permission / reserved). */
    private int skipped;

    /** Keyset cursor to pass into the next call; {@code null} once done. */
    private @Nullable String cursor;

    /** True when the folder scan is exhausted — stop looping. */
    private boolean done;
}
