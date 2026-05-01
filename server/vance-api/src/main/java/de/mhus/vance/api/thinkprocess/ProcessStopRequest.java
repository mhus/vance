package de.mhus.vance.api.thinkprocess;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body of {@code process-stop} — client requests termination of a
 * running think-process in the bound session. Triggers
 * {@code engine.stop(...)} on the target's lane and transitions the
 * process to {@link ThinkProcessStatus#CLOSED} with
 * {@link CloseReason#STOPPED}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("thinkprocess")
public class ProcessStopRequest {

    /** Name of the target process within the session. */
    @NotBlank
    private String processName;
}
