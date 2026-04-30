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
 * Result of {@code GET /brain/{tenant}/documents/kinds?projectId=}.
 *
 * <p>Distinct {@code kind:} values declared in the front matter of
 * markdown documents in the project. Powers the kind-filter dropdown
 * in the web UI's document list — server resolves with a Mongo
 * projection on the indexed {@code kind} field, never loads the body.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("documents")
public class DocumentKindsResponse {

    @Builder.Default
    private List<String> kinds = new ArrayList<>();
}
