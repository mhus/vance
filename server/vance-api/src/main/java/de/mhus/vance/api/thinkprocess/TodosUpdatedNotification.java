package de.mhus.vance.api.thinkprocess;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload of the {@code todos-updated} server notification. Sent
 * whenever Arthur's TodoList changes — initial creation via
 * {@code PROPOSE_PLAN}, status updates via {@code TODO_UPDATE}, or
 * full replacement via re-{@code PROPOSE_PLAN} (plan edit).
 *
 * <p>Carries the full current TodoList — clients replace their local
 * copy verbatim. No diff/patch protocol.
 *
 * <p>See {@code readme/arthur-plan-mode.md} §9.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("thinkprocess")
public class TodosUpdatedNotification {

    private String processId = "";

    private String processName = "";

    private String sessionId = "";

    @Builder.Default
    private List<TodoItem> todos = new ArrayList<>();
}
