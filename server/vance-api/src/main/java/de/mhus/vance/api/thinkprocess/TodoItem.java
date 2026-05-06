package de.mhus.vance.api.thinkprocess;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * A single entry in a process's TodoList. Used both as embedded
 * persistence entity in {@code ThinkProcessDocument.todos} and as DTO
 * in {@code TODOS_UPDATED} WebSocket notifications.
 *
 * <p>Granularity convention (see {@code readme/arthur-plan-mode.md}
 * §3.2.1): logical phase steps with own value (3–8 entries per list),
 * not atomic tool-calls and not over-generalisations. The decision
 * "what exactly happens" for a single entry is made when Arthur picks
 * it up during execution.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("thinkprocess")
public class TodoItem {

    /** Stable id within the process. Survives status updates and edits. */
    private String id = "";

    @Builder.Default
    private TodoStatus status = TodoStatus.PENDING;

    /** Imperative form, e.g. "Token-Storage migrieren". */
    private String content = "";

    /**
     * Optional present-continuous form for spinner/UI display, e.g.
     * "Migriere Token-Storage". {@code null} → UI falls back to {@link #content}.
     */
    private @Nullable String activeForm;
}
