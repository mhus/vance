package de.mhus.vance.api.documents;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Result of one {@code POST /brain/{tenant}/documents/rename-chunk} call. For a
 * single-document rename this returns {@code done=true} in one call; for a
 * folder rename the client loops, passing {@link #cursor} back until
 * {@link #done}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("documents")
public class DocumentRenameChunkResponse {

    /** Documents renamed (path rewritten) in this chunk. */
    private int renamed;

    /** Documents skipped in this chunk (no permission / collision). */
    private int skipped;

    /** Keyset cursor to pass into the next call; {@code null} once done. */
    private @Nullable String cursor;

    /** True when the rename is complete — stop looping. */
    private boolean done;
}
