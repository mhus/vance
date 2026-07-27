package de.mhus.vance.api.documents;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Result of one {@code POST /brain/{tenant}/documents/move-chunk} call.
 *
 * <p>The client accumulates {@link #moved} / {@link #skipped} across calls,
 * passes {@link #cursor} back into the next request, and stops when
 * {@link #done} is {@code true}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("documents")
public class DocumentMoveChunkResponse {

    /** Documents moved in this chunk. */
    private int moved;

    /** Documents skipped in this chunk (no permission or collision). */
    private int skipped;

    /** Keyset cursor to pass into the next call; {@code null} once done. */
    private @Nullable String cursor;

    /** True when the folder scan is exhausted — stop looping. */
    private boolean done;
}
