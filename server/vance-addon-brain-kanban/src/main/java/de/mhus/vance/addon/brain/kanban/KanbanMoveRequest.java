package de.mhus.vance.addon.brain.kanban;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body of {@code POST /board/move}. {@code card} is the card's full
 * document path. {@code toColumn} is the target column name (the
 * server sanitises it).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("kanban")
public class KanbanMoveRequest {

    @NotBlank
    private String card;

    @NotBlank
    private String toColumn;
}
