package de.mhus.vance.api.thinkprocess;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One entry in {@link SessionBootstrapResponse#getProcessesCreated()} /
 * {@code processesSkipped()}. Mirrors {@link ProcessCreateResponse} shape.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("thinkprocess")
public class BootstrappedProcess {

    private String thinkProcessId;

    private String name;

    private String engine;

    private ThinkProcessStatus status;
}
