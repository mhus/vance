package de.mhus.vance.api.thinkprocess;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Synchronous ack for {@code process-steer}. The actual assistant reply
 * arrives as one or more {@code chat-message-appended} notifications while
 * (and after) the engine runs its turn.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("thinkprocess")
public class ProcessSteerResponse {

    private String thinkProcessId;

    private String processName;

    private ThinkProcessStatus status;
}
