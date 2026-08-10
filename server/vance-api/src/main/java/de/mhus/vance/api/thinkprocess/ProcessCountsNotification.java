package de.mhus.vance.api.thinkprocess;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload of the {@code process-counts} server notification — how many
 * think-processes of a session are currently in each coarse state.
 * Pushed once at session welcome / resume time and afterwards whenever
 * the numbers actually change (not on every status transition).
 *
 * <p>Deliberately just counts, no per-process rows: this is the
 * trigger information for a status-bar badge ("3 running"), from which
 * the user jumps into the detail view that pulls {@code process-list}.
 * {@link ProcessSummary} stays the shape for those rows.
 *
 * <p>The session's own chat-process is <em>not</em> counted — it is
 * always there, so counting it would pin the badge at "1".
 *
 * <p>Status mapping ({@link ThinkProcessStatus}): {@code RUNNING} →
 * {@code running}; {@code BLOCKED} → {@code blocked} (waiting for the
 * user — the interesting one); {@code INIT} / {@code IDLE} /
 * {@code PAUSED} / {@code SUSPENDED} → {@code waiting};
 * {@code CLOSED} is not counted at all.
 *
 * <p>See {@code planning/process-visibility.md} §4.A.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("thinkprocess")
public class ProcessCountsNotification {

    private String sessionId = "";

    /** Processes currently executing a turn. */
    private int running = 0;

    /** Non-terminal but not executing — addressable, nobody is waiting on the user. */
    private int waiting = 0;

    /** Blocked on user input. The count worth drawing attention to. */
    private int blocked = 0;

    /** {@code running + waiting + blocked}. */
    private int total = 0;
}
