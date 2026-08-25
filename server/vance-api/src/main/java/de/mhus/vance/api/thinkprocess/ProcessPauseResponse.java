package de.mhus.vance.api.thinkprocess;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Reply for {@code process-pause}. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("thinkprocess")
public class ProcessPauseResponse {

    /**
     * Names of the processes whose pause was requested (may be empty when
     * nothing was interruptible).
     *
     * <p>Requested, not landed: the halt flag reaches a mid-turn engine
     * immediately, the {@code PAUSED} status transition runs on the process's
     * lane and therefore only after the current turn yields. Clients that need
     * the landed state watch for the {@code ENGINE_PAUSED} progress ping.
     */
    private List<String> pausedProcessNames;
}
