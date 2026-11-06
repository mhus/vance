package de.mhus.vance.shared.magrathea.journal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * An {@code agent_task} spawned a ThinkProcess. Written once per spawn, so the run knows afterwards which
 * processes worked for it — the task row forgets its process when the step ends, and without this the money a
 * run spent could not be traced back to it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessSpawnedRecord implements JournalRecord {

    /** The task that spawned it. */
    private String taskId;

    /** The spawned process. */
    private String processId;
}
