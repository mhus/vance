package de.mhus.vance.shared.magrathea;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One append-only row in {@code magrathea_journal}. Carries the typed
 * {@link de.mhus.vance.shared.magrathea.journal.JournalRecord} body as a
 * Jackson-serialised JSON string in {@link #data}; the FQN of the
 * concrete record class is stored in {@link #type} so the loader can
 * reconstitute the typed instance.
 *
 * <p>Pattern modelled after Nimbus' {@code WWorkflowJournalRecord} —
 * append only, immutable once written. Run status is reconstructed by
 * the {@code MagratheaStateProjector} from a sequence of these entries
 * (plan §3.2).
 *
 * <p>The {@code (workflowRunId, taskId, type=TaskResultRecord)} index
 * is unique — that's how the projector enforces idempotent task
 * completion in the face of pod-reclaim retries (plan §11.1).
 */
@Document(collection = "magrathea_journal")
@CompoundIndexes({
        @CompoundIndex(
                name = "run_created_idx",
                def = "{ 'workflowRunId': 1, 'createdAt': 1 }"),
        @CompoundIndex(
                name = "tenant_project_status_idx",
                def = "{ 'tenantId': 1, 'projectId': 1, 'type': 1, 'createdAt': -1 }"),
        @CompoundIndex(
                name = "run_task_result_unique_idx",
                def = "{ 'workflowRunId': 1, 'taskId': 1, 'type': 1 }",
                unique = true,
                partialFilter = "{ 'type': 'de.mhus.vance.shared.magrathea.journal.TaskResultRecord' }")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MagratheaJournalEntry {

    @Id
    private @Nullable String id;

    private String tenantId = "";
    private String projectId = "";

    /** 8-hex-prefix bucket id for the workflow run. */
    private String workflowRunId = "";

    /** FQN of the concrete {@link de.mhus.vance.shared.magrathea.journal.JournalRecord}. */
    private String type = "";

    /** Jackson-serialised record body. */
    private String data = "";

    /** Mongo {@code _id} of the {@link MagratheaTaskDocument} this entry belongs to (when applicable). */
    private @Nullable String taskId;

    /** Pod that wrote this entry. */
    private @Nullable String podId;

    private @Nullable Instant createdAt;

    @Version
    private @Nullable Long version;
}
