package de.mhus.vance.brain.magrathea;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code vance.magrathea.*} — the deadlines that keep a run from hanging
 * forever.
 *
 * <p>Every value here is a <b>net against defects</b>, not a business
 * deadline. A workflow author who has a deadline writes
 * {@code timeoutSeconds:} on the state; these defaults exist for the case
 * nobody anticipated — a subprocess that never reports, a listener that
 * never fires, a lane that wedges. Because the causes are unbounded, the
 * net must not depend on naming them.
 *
 * <p>Set any value to zero to switch that net off.
 */
@Data
@ConfigurationProperties(prefix = "vance.magrathea")
public class MagratheaProperties {

    /**
     * Deadline for an {@code agent_task} that declares none. Two hours:
     * long enough for a coding worker on a real task, short enough that a
     * subprocess which stopped reporting is cleaned up the same day.
     */
    private Duration defaultAgentTimeout = Duration.ofHours(2);

    /**
     * Deadline for a {@code gate_task} that declares none. Seven days —
     * a gate waits for a person, and a person may be on holiday. The
     * point is only that an abandoned gate does not outlive the question.
     */
    private Duration defaultGateTimeout = Duration.ofDays(7);

    /**
     * Deadline for a {@code workflow_task} (sub-run) that declares none.
     * The sub-run carries its own nets; this bounds the parent's wait if
     * the completion never propagates.
     */
    private Duration defaultSubWorkflowTimeout = Duration.ofHours(24);

    /**
     * Hard ceiling for the watchdog: a task that has sat in a
     * non-terminal state this long means its run is stalled, whatever the
     * reason, and the run is failed. This is the backstop <em>behind</em>
     * the timeouts above — it catches the case where the deadline itself
     * did not work (timer insert failed, scanner down, {@code catch:}
     * routed straight back into the same hang).
     *
     * <p>Held tasks are exempt: a paused run is stalled on purpose.
     */
    private Duration stallCeiling = Duration.ofDays(14);
}
