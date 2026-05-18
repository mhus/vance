package de.mhus.vance.api.scripts;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload for the WebSocket {@code script-execution-subscribe} message —
 * registers the current connection to receive {@code script-execution-*}
 * notifications for the given id. Reply is an empty ack via
 * {@code MessageType#SCRIPT_EXECUTION_SUBSCRIBE}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("scripts")
public class ScriptExecutionSubscribeRequest {

    private String executionId;
}
