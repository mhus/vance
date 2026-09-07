package de.mhus.vance.api.inbox;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What a compose call delivered: the users the message landed with.
 *
 * <p>One entry per inbox, not per thread: a team send fans out to one thread
 * per member (each carries its own read state and its own clarification), so
 * the count is the honest answer to "who got this?" — a team name alone would
 * not say how many desks it reached, and members the delivery gate refused
 * are simply not in the list.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("inbox")
public class InboxComposeResponse {

    private List<String> deliveredTo;
}
