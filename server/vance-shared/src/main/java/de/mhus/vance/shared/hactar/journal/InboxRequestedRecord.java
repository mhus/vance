package de.mhus.vance.shared.hactar.journal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Gate-task created an InboxItem and is now waiting on the user.
 * Carries the InboxItem id so the projector can render the active
 * pending interaction.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InboxRequestedRecord implements JournalRecord {

    private String state;

    /** Mongo id of the linked {@code InboxItem}. */
    private String inboxItemId;

    /** {@code APPROVAL}, {@code DECISION}, or {@code FEEDBACK}. */
    private String inboxKind;
}
