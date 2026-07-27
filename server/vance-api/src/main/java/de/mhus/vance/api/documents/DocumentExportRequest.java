package de.mhus.vance.api.documents;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body of {@code POST /brain/{tenant}/documents/export}.
 *
 * <p>The server streams a ZIP archive of the listed documents, one entry per
 * document keyed by its document path so the (virtual) folder structure is
 * preserved. Every id is resolved and {@code READ}-authorized up front — the
 * ZIP body only starts once all ids passed, so a missing/foreign/forbidden id
 * fails the whole request with a clean status instead of a corrupt archive.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("documents")
public class DocumentExportRequest {

    /** Document ids to include. Must be non-empty. */
    @NotEmpty
    private List<String> ids;
}
