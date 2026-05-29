package de.mhus.vance.api.kanban;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of {@code POST /board/rebuild} — the regenerated artefact
 * descriptors. Use the {@code path} for a fresh link or to refetch
 * the artefact body.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("kanban")
public class KanbanRebuildResponse {

    private String folder;

    @Builder.Default
    private List<KanbanArtefactSummary> artefacts = new ArrayList<>();
}
