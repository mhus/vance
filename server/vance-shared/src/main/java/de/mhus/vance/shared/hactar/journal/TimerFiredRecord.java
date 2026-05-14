package de.mhus.vance.shared.hactar.journal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Timer-scanner observed {@code fireAt ≤ now} and moved the timer to
 * a runnable {@link de.mhus.vance.shared.hactar.HactarTaskDocument}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimerFiredRecord implements JournalRecord {
    private String timerId;
    private String targetState;
}
