package de.mhus.vance.api.backup;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body for {@code POST /brain/{tenant}/backup/projects/{project}/entries}.
 *
 * <p>Paginated by {@link #cursor} (last seen {@code _id}) rather than by
 * page number — page numbers shift when documents are added or removed
 * between calls, which would silently drop or duplicate entries. Cursor
 * pagination is stable across concurrent writes (planning/user-backup.md §6.1).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("backup")
public class BackupEntriesRequest {

    /**
     * Glob patterns to deny within this project (e.g. {@code "_vance/**"}).
     * Server reduces the transferred list by rejecting these paths before
     * sending them; client also applies the same filter as a defence-in-depth.
     */
    private @Nullable List<String> deny;

    /** Per-project deny override; takes precedence over {@link #deny} for this project. */
    private @Nullable List<String> projectDeny;

    /**
     * {@code _id} of the last entry from the previous page, or {@code null}
     * for the first page. The query returns entries with {@code _id >
     * cursor}, sorted ascending by {@code _id}.
     */
    private @Nullable String cursor;

    /** Maximum number of entries to return. Server caps at its own maximum. */
    private int size;
}
