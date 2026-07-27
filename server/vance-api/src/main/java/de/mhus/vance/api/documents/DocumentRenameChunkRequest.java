package de.mhus.vance.api.documents;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /brain/{tenant}/documents/rename-chunk}.
 *
 * <p>Renames a document or a (virtual) folder. A {@link #path} ending in
 * {@code '/'} is treated as a folder prefix and renamed as a prefix
 * substitution over every document beneath it — a client-driven, cursor-paged
 * chunk loop with per-document {@code WRITE} checks (skip what you can't
 * write), exactly like the chunked move. Any other {@code path} is a single
 * document rename that completes in one call. {@link #newName} is a single path
 * segment (no {@code '/'}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("documents")
public class DocumentRenameChunkRequest {

    /** Document path, or folder prefix (trailing {@code '/'}) to rename. */
    @NotBlank
    private String path;

    /** New last segment (basename for a file, folder name for a folder). */
    @NotBlank
    private String newName;

    /** Max documents to process this call (folder rename). Server clamps. */
    private @Nullable Integer limit;

    /** Keyset cursor (last processed path) from the previous response. */
    private @Nullable String cursor;
}
