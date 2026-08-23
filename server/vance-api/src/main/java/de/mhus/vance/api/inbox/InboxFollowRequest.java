package de.mhus.vance.api.inbox;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body of {@code POST /brain/{tenant}/inbox/{id}/follow} — subscribe to or
 * unsubscribe from a thread's updates.
 *
 * <p>One endpoint for both directions because it is a toggle. Subscribing does
 * <em>not</em> create unread (you are looking at the thread already);
 * unsubscribing clears it, or a badge would stay lit for a thread you asked to
 * be rid of.
 *
 * <p>Refused with 409 for the assignee of an open ask: a process is waiting on
 * them, and going quiet would strand it. Delegating is the way out.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("inbox")
public class InboxFollowRequest {
    private boolean following;
}
