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
 * <p>Lists every folder path that contains at least one active
 * document in the project — sorted alphabetically. Used by the
 * web UI's path-filter combobox as the dropdown options.
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
}
