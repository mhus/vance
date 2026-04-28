package de.mhus.vance.api.teams;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Compact team representation for list endpoints. Includes the
 * member-username list so the inbox UI can compute the "team
 * inbox" filter (assignedToUserId IN members) without a second
 * round-trip per team.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("teams")
public class TeamSummary {

    private String id = "";
    private String name = "";
    private @Nullable String title;

    @Builder.Default
    private List<String> members = new ArrayList<>();

    private boolean enabled;
}
