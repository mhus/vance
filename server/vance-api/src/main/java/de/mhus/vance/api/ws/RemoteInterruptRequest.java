package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Watcher → Brain → foot: interrupt what the client is doing.
 *
 * <p>Two strengths, matching the two things a human at the terminal can do:
 * {@code hard=false} asks the engine to pause (the ESC path), {@code hard=true}
 * stops the turn outright.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ws")
public class RemoteInterruptRequest {

    /** Target client. */
    private String clientId;

    /** {@code false} = pause, {@code true} = stop. */
    private boolean hard;
}
