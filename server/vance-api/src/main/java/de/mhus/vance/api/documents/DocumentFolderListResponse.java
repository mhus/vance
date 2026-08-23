package de.mhus.vance.api.documents;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Folder-listing view for the documents editor — combines the
 * sub-folder names directly under the requested path with a paged
 * list of files in that same directory (one level deep only).
 *
 * <p>Returned by {@code GET /brain/{tenant}/documents/folder?projectId=&path=&page=&size=}.
 *
 * <p>{@link #folders} is the alphabetically-sorted list of next-level
 * folder names found under the path. It's not paged — folders are
 * typically few enough to fit on screen in one go, and trying to page
 * them adds complexity without a real use case.
 *
 * <p>{@link #files} is the paged list of documents whose path is
 * exactly {@code <requestedPath>/<filename>} — i.e. files directly in
 * this folder, no sub-folders descended. {@code totalCount} counts
 * files only.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("documents")
public class DocumentFolderListResponse {

    @Builder.Default
    private List<String> folders = new ArrayList<>();

    @Builder.Default
    private List<DocumentSummary> files = new ArrayList<>();

    private int page;

    private int pageSize;

    private long totalCount;

    /**
     * Only set when a search term was given inside a mounted folder
     * ({@code _ext/<mount>/…}) — see {@link MountSearchOutcome}.
     *
     * <p>{@code DELEGATED} means the hits came from the external source and
     * span the <b>whole mount</b>, not the folder being browsed, and that
     * {@link #folders} is empty because a hit list is not a folder view.
     * {@code UNSUPPORTED} and {@code UNAVAILABLE} mean the question was not
     * answered at all — a client must not present those as "nothing found".
     */
    private @Nullable MountSearchOutcome mountSearch;
}
