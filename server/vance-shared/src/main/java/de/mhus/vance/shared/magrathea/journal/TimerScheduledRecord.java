package de.mhus.vance.shared.magrathea.journal;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Timer was added to {@code magrathea_timers}. The
 * {@link de.mhus.vance.shared.magrathea.MagratheaTimerDocument} carries the
 * authoritative state; this journal entry is the audit marker.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimerScheduledRecord implements JournalRecord {
    private String timerId;
    private Instant fireAt;
    private String targetState;
}
