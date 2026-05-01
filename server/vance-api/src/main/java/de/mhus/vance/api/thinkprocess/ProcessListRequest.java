package de.mhus.vance.api.thinkprocess;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Optional request payload for {@code process-list}. {@code null}
 * data is treated as defaults (no terminated processes shown).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("thinkprocess")
public class ProcessListRequest {

    /**
     * Include processes in the terminal {@link ThinkProcessStatus#CLOSED}
     * state. Default {@code false} — closed rows are audit-only and
     * clutter the live view.
     */
    private boolean includeTerminated;
}
