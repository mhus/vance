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
    private @Nullable String homeCluster;

    private @Nullable Instant claimedAt;

    private @Nullable Instant createdAt;
}
