package de.mhus.vance.shared.hactar.journal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Free-form debug/audit annotation. Carries no semantic meaning for
 * the state machine — handy for executor instrumentation and ad-hoc
 * commentary during incident response. Analog Nimbus' {@code NoteRecord}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NoteRecord implements JournalRecord {
    private String note;
}
