package de.mhus.vance.api.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload of the {@code session-roster} server notification — see
 * {@code planning/multi-user-sessions.md} §7. Pushed to every
 * connection in a session whenever the participant list changes
 * (join, leave, kick-old, bind-escalation).
 *
 * <p>Clients render an avatar stack / participant list from this
 * frame and update incrementally as new frames arrive. The full
 * roster is sent each time — simpler than emitting per-participant
 * deltas and the volume is tiny (typically &lt;10 participants).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("session")
public class SessionRosterData {

    private String sessionId;
    private List<SessionParticipantDto> participants;
}
