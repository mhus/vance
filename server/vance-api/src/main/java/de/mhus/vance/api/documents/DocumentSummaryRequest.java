package de.mhus.vance.api.documents;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body of {@code PUT /brain/{tenant}/documents/{id}/summary}. An
 * empty or blank {@code summary} clears the existing value.
 *
 * <p>Separate endpoint (rather than reusing {@code DocumentUpdateRequest})
 * because summary edits are a single-field write triggered both from
 * the UI's caption editor and from the {@code doc_set_summary} tool;
 * keeping the wire payload minimal makes both call sites obvious.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("documents")
public class DocumentSummaryRequest {

    private String summary;
}
