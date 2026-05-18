package de.mhus.vance.api.scripts;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Returned by {@code POST /scripts/generate}. The UI then watches the
 * Deep-Thought process via the {@code process-progress} WebSocket
 * channel and, on DONE, fetches the generated code through
 * {@code GET /scripts/generations/{thinkProcessId}/result}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("scripts")
public class ScriptGenerateResponse {

    private String thinkProcessId;
    private String processName;
}
