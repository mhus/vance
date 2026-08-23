package de.mhus.vance.api.inbox;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body of {@code POST /brain/{tenant}/inbox/{id}/invite} — pull someone into a
 * thread.
 *
 * <p>Inviting <em>is</em> delivering, so it is authorized like a delivery:
 * {@code Resource.InboxItem} + {@code WRITE} on the invitee's inbox, the same
 * check Milliways' inbox handler uses. The invitation makes the thread unread
 * for them — it comes from someone else and has to be noticeable.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("inbox")
public class InboxInviteRequest {

    @NotBlank
    private String userId;
}
