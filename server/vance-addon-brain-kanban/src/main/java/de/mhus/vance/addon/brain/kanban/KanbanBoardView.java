package de.mhus.vance.addon.brain.kanban;

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
 * Full state of a Kanban board — what the {@code GET /board}
 * endpoint returns. Columns are in render-order, cards are NOT
 * pre-grouped (the UI groups by {@code card.column}); per-column
 * sort happens client-side.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("kanban")
public class KanbanBoardView {

    private String folder;

    private String manifestPath;

    private @Nullable String title;

    private @Nullable String description;

    /** {@code "soft"} or {@code "hard"} — same wire as
     *  {@code KanbanAppConfig.WipEnforce}. */
    private String wipEnforce;

    /** {@code "mermaid"} or {@code "table"}. */
    private String boardStyle;

    @Builder.Default
    private List<KanbanColumnView> columns = new ArrayList<>();

    @Builder.Default
    private List<KanbanCardView> cards = new ArrayList<>();

    @Builder.Default
    private List<KanbanArtefactSummary> artefacts = new ArrayList<>();
}
