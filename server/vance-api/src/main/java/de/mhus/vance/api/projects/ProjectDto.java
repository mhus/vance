package de.mhus.vance.api.projects;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Full read view of a project for the admin editor — richer than the
 * list-friendly {@code ProjectSummary} (adds status, teams, claim metadata).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("projects")
public class ProjectDto {

    private String name;

    private @Nullable String title;

    private @Nullable String projectGroupId;

    @Builder.Default
    private List<String> teamIds = new ArrayList<>();

    private boolean enabled;

    /** {@code PENDING} / {@code ACTIVE} / {@code SUSPENDED} / {@code ARCHIVED}. */
    private String status;

    /**
     * Cluster node name of the pod owning the project — references
     * the {@code BrainPodDocument.nodeName} of an entry in the
     * cluster registry. The actual {@code host:port} is resolved on
     * demand server-side; the UI receives only the human-readable
     * cluster identifier.
     */
    private @Nullable String homeNode;

    private @Nullable Instant claimedAt;

    /**
     * {@code AUTO} / {@code EPHEMERAL} / {@code PERMANENT} / {@code HOMELESS} —
     * the operator override over {@link #ownerRequired}. {@code AUTO} means
     * "let the derived flag decide" and is the default for user projects.
     */
    private @Nullable String lifecycleType;

    /**
     * Derived: the project holds background work (schedulers, hooks, event
     * triggers, kit provisioning) and therefore has to be kept on a live pod.
     * Read-only — it follows the documents, it is not set by hand.
     */
    private boolean ownerRequired;

    private @Nullable Instant createdAt;
}
