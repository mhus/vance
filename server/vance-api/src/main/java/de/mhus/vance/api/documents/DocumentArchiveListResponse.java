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
 * Response of {@code GET /brain/{tenant}/documents/{id}/archives}. Carries
 * the version count (UI badge) plus the per-archive summaries sorted by
 * {@code archivedAtMs} descending.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("documents")
public class DocumentArchiveListResponse {

    private long totalCount;

    @Builder.Default
    private List<DocumentArchiveSummary> items = new ArrayList<>();
}
