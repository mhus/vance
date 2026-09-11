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
 * The persisted plan state of one process, carried in the
 * {@code session-resume} / {@code session-bootstrap} reply so a freshly
 * bound client can restore its plan/todo UI without a round-trip — the
 * plan-state sibling of {@link ActiveProcessRef} (same carrier, same
 * ordering rationale: the reply travels the connection before any live
 * {@code todos-updated} / {@code process-mode-changed} frame the client
 * could correlate against, and the client only learns its chat-process
 * pointer after the bind completes).
 *
 * <p>Only processes with something to show are included: a non-{@code NORMAL}
 * mode (Arthur/Eddie Plan-Mode) or a non-empty todo list (Arthur/Eddie
 * plan steps, Frankie/Benjy TodoList projection). Closed processes are
 * skipped — their todos are a historical record, not a live plan.
 *
 * <p>See {@code specification/public/live-ws.md} §5.2b.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("thinkprocess")
public class ProcessPlanState {

    /** Think-process id. */
    private String processId = "";

    /** Technical process name within the session, e.g. {@code "chat"}. */
    private String processName = "";

    /** Persisted operating mode — {@code NORMAL} for non-plan-mode engines. */
    @Builder.Default
    private ProcessMode mode = ProcessMode.NORMAL;

    /** Persisted todo list — empty when only the mode is relevant. */
    @Builder.Default
    private List<TodoItem> todos = new ArrayList<>();
}
