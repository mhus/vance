package de.mhus.vance.api.kanban;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One column on a Kanban board. Mirrors
 * {@code KanbanAppConfig.Column} plus the runtime card count + WIP
 * status. {@code declared = false} marks columns that exist on disk
 * (a sub-folder with cards) but were never listed in {@code _app.yaml}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("kanban")
public class KanbanColumnView {

    private String name;

    private @Nullable String title;

    private @Nullable String color;

    private @Nullable Integer order;

    private @Nullable Integer wipLimit;

    private int cardCount;

    private boolean wipExceeded;

    private boolean declared;
}
