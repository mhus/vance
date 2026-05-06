package de.mhus.vance.api.thinkprocess;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload of the {@code process-mode-changed} server notification.
 * Sent whenever Arthur transitions {@link ProcessMode} (e.g.
 * NORMAL → EXPLORING via {@code START_PLAN}, EXPLORING → PLANNING
 * via {@code PROPOSE_PLAN}, PLANNING → EXECUTING via
 * {@code START_EXECUTION}).
 *
 * <p>Drives Foot's mode-indicator in the prompt and the plan-box
 * visibility in the scrollback.
 *
 * <p>See {@code readme/arthur-plan-mode.md} §9.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("thinkprocess")
public class ProcessModeChangedNotification {

    private String processId = "";

    private String processName = "";

    private String sessionId = "";

    private ProcessMode oldMode = ProcessMode.NORMAL;

    private ProcessMode newMode = ProcessMode.NORMAL;
}
