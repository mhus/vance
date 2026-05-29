package de.mhus.vance.api.kanban;

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
 * One card on a Kanban board. The {@code path} is the document's
 * full path and serves as the card's stable id from the client's
 * perspective — moving a card via the REST API turns into a
 * {@code DocumentService.update(newPath=...)} that preserves the
 * document id.
 *
 * <p>{@code subtaskTotal} / {@code subtaskDone} count GFM checkboxes
 * in the body so the UI can render a progress badge without parsing
 * markdown on its own.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("kanban")
public class KanbanCardView {

    private String path;

    private String column;

    private String title;

    private @Nullable String priority;

    private @Nullable String assignee;

    @Builder.Default
    private List<String> labels = new ArrayList<>();

    private @Nullable String dueDate;

    private @Nullable Double estimate;

    private boolean blocked;

    private @Nullable String body;

    private int subtaskTotal;

    private int subtaskDone;
}
