package de.mhus.vance.api.teams;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of {@code GET /brain/{tenant}/teams}. v1 returns only
 * the teams the requesting user is a member of — Tenant-wide
 * admin views are out of scope.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("teams")
public class TeamListResponse {
    @Builder.Default
    private List<TeamSummary> teams = new ArrayList<>();
}
