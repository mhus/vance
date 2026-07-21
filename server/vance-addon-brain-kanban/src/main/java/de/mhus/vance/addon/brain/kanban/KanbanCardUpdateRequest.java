package de.mhus.vance.addon.brain.kanban;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code PATCH /board/cards/{path}}. All fields are optional —
 * {@code null} means "leave untouched". An explicit empty list / empty
 * string clears the corresponding card field.
 *
 * <p>{@code blocked} is a tri-state on the wire ({@link Boolean}) so
 * {@code null} is distinguishable from {@code false} ("set to false").
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("kanban")
public class KanbanCardUpdateRequest {

    private @Nullable String title;

    private @Nullable String priority;

    private @Nullable String assignee;

    private @Nullable List<String> labels;

    private @Nullable String dueDate;

    private @Nullable Double estimate;

    private @Nullable Boolean blocked;

    /**
     * Document-level accent color as the {@code AccentColor} enum name
     * (e.g. {@code "RED"}). {@code null} = leave untouched; clearing goes
     * through {@link #clearColor} since the name can't signal "clear".
     */
    private @Nullable String color;

    /** When {@code true}, clears the accent color back to neutral. */
    private @Nullable Boolean clearColor;

    private @Nullable String body;
}
