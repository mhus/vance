package de.mhus.vance.shared.hactar.journal;

import tools.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * User answered the inbox item produced by an earlier
 * {@link InboxRequestedRecord}. The {@code outcome} drives the
 * state-graph transition; the {@code value} carries the structured
 * payload for {@code storeAs:}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InboxAnsweredRecord implements JournalRecord {

    private String inboxItemId;

    /** {@code approved} / {@code rejected} for APPROVAL, the chosen option for DECISION, free text marker for FEEDBACK. */
    private String outcome;

    private @Nullable JsonNode value;
}
