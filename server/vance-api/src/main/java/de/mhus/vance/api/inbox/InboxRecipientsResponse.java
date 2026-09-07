package de.mhus.vance.api.inbox;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One page of the compose dialog's recipient search: users the caller may
 * deliver to plus the caller's teams, both matching the query.
 *
 * <p>The list is provider-filtered — for users, the same {@code InboxItem
 * WRITE} check the delivery itself passes — so the picker cannot offer a
 * recipient the send would then refuse. Bounded, and the bound is reported:
 * {@code truncated} means the page is full and refining the query may show
 * more, never that these are all of them.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("inbox")
public class InboxRecipientsResponse {

    private List<InboxRecipientDto> recipients;

    /** The query matched more than the page shows. */
    private boolean truncated;
}
