package de.mhus.vance.api.thinkprocess;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One process that is mid-turn, named both ways because its two readers
 * address it differently.
 *
 * <p>A client correlating against turn-boundary pings needs the
 * {@code processId} those pings carry; a client correlating against the
 * session's chat-process needs the {@code name}. Emitting one and letting
 * the other side look up the missing half is what puts a second round-trip
 * (and a race against the pings) back into a reconnect.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("thinkprocess")
public class ActiveProcessRef {

    /** Think-process id — the key the {@code process-progress} pings use. */
    private String processId;

    /** Technical process name within the session, e.g. {@code "chat"}. */
    private String name;
}
