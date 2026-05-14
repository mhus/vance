package de.mhus.vance.shared.hactar.journal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * State machine entered a new state. Written by the type-dispatcher
 * before {@link TaskStartedRecord} so projectors and observers see the
 * state change before the execution starts.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StateEnteredRecord implements JournalRecord {
    private String state;
}
