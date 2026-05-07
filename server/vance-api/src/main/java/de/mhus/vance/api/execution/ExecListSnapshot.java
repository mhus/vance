package de.mhus.vance.api.execution;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Foot → Brain push, sent at connection time (and on reconnect):
 * complete list of currently known foot-side jobs. Each entry follows
 * the {@link ExecEvent#getKind()} {@code STARTED} schema, with status
 * filled in to reflect the current state. Brain replaces every entry
 * it owns for this connection with this snapshot — the canonical
 * reconciliation point after a brain or foot restart.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("execution")
public class ExecListSnapshot {

    @Builder.Default
    private List<ExecEvent> executions = new ArrayList<>();
}
