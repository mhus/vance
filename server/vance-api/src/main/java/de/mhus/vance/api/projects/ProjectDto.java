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

    private @Nullable String podIp;

    private @Nullable Instant claimedAt;

    private @Nullable Instant createdAt;
}
