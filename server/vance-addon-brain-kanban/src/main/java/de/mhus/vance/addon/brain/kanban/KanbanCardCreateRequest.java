package de.mhus.vance.addon.brain.kanban;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /board/cards}. Identical shape to a card payload
 * inside {@code kanban_app_create} (so the wire is consistent across
 * REST and tool calls).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("kanban")
public class KanbanCardCreateRequest {

    @NotBlank
    private String title;

    /** Target column — defaults server-side to "backlog" when null. */
    private @Nullable String column;

    private @Nullable String priority;

    private @Nullable String assignee;

    @Builder.Default
    private List<String> labels = new ArrayList<>();

    private @Nullable String dueDate;

    private @Nullable Double estimate;

    private boolean blocked;

    private @Nullable String body;

    /** Optional filename slug (without extension). Auto-slugged from
     *  {@code title} when null. */
    private @Nullable String filename;
}
