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
 * Result of a card move. {@code card} is the new path. {@code warnings}
 * carries WIP-overflow notices when {@code wipEnforce=soft}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("kanban")
public class KanbanMoveResponse {

    private String card;

    private String fromColumn;

    private String toColumn;

    @Builder.Default
    private List<String> warnings = new ArrayList<>();
}
