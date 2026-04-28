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
 * Paged result of {@code GET /brain/{tenant}/documents?projectId=&page=&size=}.
 *
 * <p>{@code page} is zero-based to match Spring Data's convention.
 * {@code totalCount} is the total across all pages, not the size of {@link #items}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("documents")
public class DocumentListResponse {

    @Builder.Default
    private List<DocumentSummary> items = new ArrayList<>();

    private int page;

    private int pageSize;

    private long totalCount;
}
