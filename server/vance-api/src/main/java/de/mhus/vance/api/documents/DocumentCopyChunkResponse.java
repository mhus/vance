package de.mhus.vance.api.documents;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Result of one {@code POST /brain/{tenant}/documents/copy-chunk} call.
 *
 * <p>The client accumulates {@link #copied} / {@link #skipped} across calls,
 * passes {@link #cursor} back into the next request, and stops when
 * {@link #done} is {@code true}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("documents")
public class DocumentCopyChunkResponse {

    /** Documents newly created in this chunk. */
    private int copied;

    /**
     * Documents replaced in this chunk because they already existed at the
     * destination and {@code overwrite} was requested. Always {@code 0}
     * without that flag — counted separately from {@link #copied} so the
     * client can tell "wrote 12 new files" from "replaced 12 existing ones".
     */
    private int overwritten;

    /** Documents skipped in this chunk (no permission, lock or collision). */
    private int skipped;

    /** Keyset cursor to pass into the next call; {@code null} once done. */
    private @Nullable String cursor;

    /** True when the folder scan is exhausted — stop looping. */
    private boolean done;
}
