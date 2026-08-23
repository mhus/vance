package de.mhus.vance.api.inbox;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body of {@code POST /brain/{tenant}/inbox/{id}/participants/remove} — take
 * someone back out of a thread.
 *
 * <p>The counterpart to {@code /invite}, and the reason it is needed: being a
 * participant is checked before anything derived, so joining a thread turns a
 * visibility that <em>followed the assignee</em> into one that stays. Without
 * this, an unwanted participant could only be undone by that participant.
 *
 * <p>Deliberately a separate request type from {@link InboxInviteRequest}
 * despite the identical shape — one authorizes against the invitee's inbox and
 * grows the thread, the other is an act of running the matter and is gated on
 * whoever may decide. Sharing the type would invite sharing the handler.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("inbox")
public class InboxParticipantRemoveRequest {

    @NotBlank
    @Size(max = 256)
    private String userId;
}
