package de.mhus.vance.api.documents;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of {@code GET /brain/{tenant}/documents/folders?projectId=}.
 *
 * <p>The project's folders as <b>destinations</b> — where a document may be
 * moved or copied to. Sorted alphabetically. Used by the web UI's move/copy
 * dialogs as the target suggestions.
 *
 * <p>Two things it deliberately is not: complete, and a view of the namespace.
 * {@code _ext/**} (mounted sources) and {@code _vance/trash/**} are excluded —
 * a mount is somebody else's file system, and the trash is where documents go
 * to be forgotten; neither is a place to move a document to — and the list is
 * capped ({@code vance.documents.folder-list-limit}), with {@link #truncated}
 * saying so. For "what is there", use {@code GET documents/folder}, which
 * pages one folder at a time and does include both.
 *
 * <p>The server resolves this <em>without</em> loading the full
 * documents — a Mongo projection on the {@code path} field only,
 * folders derived in-process by splitting at {@code /}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("documents")
public class DocumentFoldersResponse {

    @Builder.Default
    private List<String> folders = new ArrayList<>();

    /**
     * More folders exist than are listed. The client has to say so: a
     * suggestion list that simply ends reads as "there is nowhere else",
     * and the user's actual destination may be the one that got cut.
     */
    private boolean truncated;
}
