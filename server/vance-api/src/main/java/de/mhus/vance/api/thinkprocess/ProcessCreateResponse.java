package de.mhus.vance.api.thinkprocess;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Reply to {@code process-create}. {@code thinkProcessId} is the technical
 * Mongo id; {@code name} is the process's per-session identifier.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("thinkprocess")
public class ProcessCreateResponse {

    private String thinkProcessId;

    private String name;

    private String engine;

    private ThinkProcessStatus status;
}
